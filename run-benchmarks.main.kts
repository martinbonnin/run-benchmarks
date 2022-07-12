#!/usr/bin/env kotlin

@file:DependsOn("com.squareup.okio:okio:3.2.0")
@file:DependsOn("com.google.cloud:google-cloud-storage:2.8.1")
@file:DependsOn("net.mbonnin.bare-graphql:bare-graphql:0.0.2")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.10.0")

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.mbonnin.bare.graphql.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.buffer
import okio.source
import java.io.File
import java.util.Date
import kotlin.math.roundToLong


/**
 * This script expects:
 *
 * - `gcloud` in the path
 * - A Google Cloud Project with "Google Cloud Testing API" and "Cloud Tool Results API" enabled
 * - GITHUB_REPOSITORY env variable: the repository to create the issue into in the form `owner/name`
 *
 * This script must be run from the repo root
 */

fun getInput(name: String): String {
    return getOptionalInput(name) ?: error("Cannot find an input for $name")
}

fun getOptionalInput(name: String): String? {
    return System.getenv("INPUT_${name.uppercase()}")?.ifBlank {
        null
    }
}

/**
 * Executes the given command and returns stdout as a String
 * Throws if the exit code is not 0
 */
fun executeCommand(vararg command: String): CommandResult {
    val process = ProcessBuilder()
        .command(*command)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .start()

    /**
     * Read output and error in a thread to not block the process if the output/error
     * doesn't fit in the buffer
     */
    var output: String? = null
    var error: String? = null
    val outputThread = Thread {
        val buffer = process.inputStream.source().buffer()
        output = buildString {
            while (true) {
                val line = buffer.readUtf8Line()
                if (line == null) {
                    break
                }
                println("STDOUT: $line")
                appendLine(line)
            }
        }
    }
    outputThread.start()
    val errorThread = Thread {
        val buffer = process.errorStream.source().buffer()
        error = buildString {
            while (true) {
                val line = buffer.readUtf8Line()
                if (line == null) {
                    break
                }
                println("STDERR: $line")
                appendLine(line)
            }
        }
    }
    errorThread.start()

    val exitCode = process.waitFor()

    outputThread.join()
    errorThread.join()
    return CommandResult(exitCode, output ?: "", error ?: "")
}

class CommandResult(val code: Int, val stdout: String, val stderr: String)


/**
 * Authenticates the local 'gcloud' and a new [Storage] instance
 * Throws on error
 */
fun authenticate(): GCloud {
    val googleServicesJson = getInput("GOOGLE_SERVICES_JSON")

    val tmpFile: File = File.createTempFile("google", "json")
    val credentials: GoogleCredentials
    val storage: Storage
    try {
        tmpFile.writeText(googleServicesJson)
        val result = executeCommand("gcloud", "auth", "activate-service-account", "--key-file=${tmpFile.absoluteFile}")
        if (result.code != 0) {
            error("Cannot authenticate")
        }
        credentials = GoogleCredentials.fromStream(tmpFile.inputStream())
            .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
        storage = StorageOptions.newBuilder().setCredentials(credentials).build().service
    } finally {
        tmpFile.delete()
    }

    val jsonElement = Json.parseToJsonElement(googleServicesJson)

    return GCloud(
        storage,
        jsonElement.jsonObject.get("project_id")?.jsonPrimitive?.content ?: error("Cannot find project_id")
    )
}

data class GCloud(val storage: Storage, val projectId: String)

fun runTest(projectId: String): String {
    val result = executeCommand(
        "gcloud",
        "-q", // Disable all interactive prompts
        "--project",
        projectId,
        "firebase",
        "test",
        "android",
        "run",
        "--type",
        "instrumentation",
        "--device",
        "model=${getInput("device_model")}",
        "--test",
        getInput("test_apk"),
        "--app",
        getInput("app_apk"),
    )

    check(result.code == 0) {
        "Test failed"
    }

    // Most of the interesting output is in stderr
    return result.stderr
}

/**
 * Parses the 'gcloud firebase test android run' output and download the instrumentation
 * results from Google Cloud Storage
 *
 * @return the [TestResult]
 */
fun getTestResult(output: String, storage: Storage): TestResult {
    val gsUrl = output.lines().mapNotNull {
        val matchResult =
            Regex(".*\\[https://console.developers.google.com/storage/browser/([^\\]]*).*").matchEntire(it)
        matchResult?.groupValues?.get(1)
    }.single()
        .split("/")
        .filter { it.isNotBlank() }
    val bucket = gsUrl[0]
    val blobName = "${gsUrl[1]}/redfin-30-en-portrait/instrumentation.results"

    println("Downloading $bucket/$blobName")
    val buffer = Buffer()
    storage.get(bucket, blobName).downloadTo(buffer.outputStream())

    val cases = buffer.readUtf8().parseCases()

    val firebaseUrl = output.lines().mapNotNull {
        val matchResult = Regex("Test results will be streamed to \\[(.*)\\].").matchEntire(it)
        matchResult?.groupValues?.get(1)
    }.single()

    return TestResult(firebaseUrl, cases)
}

/**
 * Heuristics based parser until Firebase Test Labs supports downloading the Json
 */
fun String.parseCases(): List<Case> {
    val cases = mutableListOf<Case>()
    var clazz: String? = null
    var test: String? = null
    var nanos: Long? = null
    var allocs: Long? = null

    val clazzRegex = Regex("INSTRUMENTATION_STATUS: class=(.*)")
    val testRegex = Regex("INSTRUMENTATION_STATUS: test=(.*)")
    val nanosRegex = Regex("INSTRUMENTATION_STATUS: time_nanos_median=(.*)")
    val allocsRegex = Regex("INSTRUMENTATION_STATUS: allocation_count_median=(.*)")

    fun maybeOutput() {
        if (clazz != null && test != null && nanos != null && allocs != null) {
            cases.add(Case(clazz!!, test!!, nanos!!, allocs!!))
            clazz = null
            test = null
            nanos = null
            allocs = null
        }
    }
    lines().forEach {
        var result = clazzRegex.matchEntire(it)
        if (result != null) {
            clazz = result.groupValues[1]
            maybeOutput()
            return@forEach
        }
        result = testRegex.matchEntire(it)
        if (result != null) {
            test = result.groupValues[1]
            maybeOutput()
            return@forEach
        }
        result = nanosRegex.matchEntire(it)
        if (result != null) {
            nanos = result.groupValues[1].toDouble().roundToLong()
            maybeOutput()
            return@forEach
        }
        result = allocsRegex.matchEntire(it)
        if (result != null) {
            allocs = result.groupValues[1].toDouble().roundToLong()
            maybeOutput()
            return@forEach
        }
    }

    return cases
}

data class TestResult(
    val firebaseUrl: String,
    val cases: List<Case>,
)

data class Case(
    val clazz: String,
    val test: String,
    val nanos: Long,
    val allocs: Long,
) {
    val fqName = "${clazz}.$test"
}

fun issueBody(testResult: TestResult): String {
    return buildString {
        appendLine("${Date()}")
        appendLine("[${testResult.firebaseUrl}](${testResult.firebaseUrl})")
        appendLine()
        appendLine("Test Cases:")
        appendLine("| Test Case | Nanos | Allocs |")
        appendLine("|-----------|-------|--------|")
        testResult.cases.forEach {
            appendLine("|${it.fqName}|${it.nanos}|${it.allocs}|")
        }
    }
}

val issueTitle = "Benchmarks dashboard"
fun updateOrCreateGithubIssue(testResult: TestResult) {
    val ghRepo = System.getenv("GITHUB_REPOSITORY") ?: error("GITHUB_REPOSITORY env variable is missing")
    val ghRepositoryOwner = ghRepo.split("/")[0]
    val ghRepositoryName = ghRepo.split("/")[1]

    val query = """
{
  search(query: "$issueTitle repo:$ghRepo", type: ISSUE, first: 100) {
    edges {
      node {
        ... on Issue {
          title
          id
        }
      }
    }
  }
  repository(owner: "$ghRepositoryOwner", name: "$ghRepositoryName") {
    id
  }
}
""".trimIndent()


    val response = ghGraphQL(query)
    val existingIssues = response.get("search").asMap.get("edges").asList

    val mutation: String
    val variables: Map<String, String>
    if (existingIssues.isEmpty()) {
        mutation = """
mutation createIssue(${'$'}repositoryId: ID!, ${'$'}title: String!, ${'$'}body: String!) {
  createIssue(input: {repositoryId: ${'$'}repositoryId, title: ${'$'}title, body: ${'$'}body} ){
    clientMutationId
  }
}
    """.trimIndent()
        variables = mapOf(
            "title" to issueTitle,
            "body" to issueBody(testResult),
            "repositoryId" to response.get("repository").asMap["id"].cast<String>()
        )
        println("creating issue")
    } else {
        mutation = """
mutation updateIssue(${'$'}id: ID!, ${'$'}body: String!) {
  updateIssue(input: {id: ${'$'}id, body: ${'$'}body} ){
    clientMutationId
  }
}
    """.trimIndent()
        variables = mapOf(
            "id" to existingIssues.first().asMap["node"].asMap["id"].cast<String>(),
            "body" to issueBody(testResult)
        )
        println("updating issue")
    }
    ghGraphQL(mutation, variables)
}

fun ghGraphQL(operation: String, variables: Map<String, String> = emptyMap()): Map<String, Any?> {
    val ghToken = getInput("github_token")
    val headers = mapOf("Authorization" to "bearer $ghToken")
    return graphQL(
        url = "https://api.github.com/graphql",
        operation = operation,
        headers = headers,
        variables = variables
    ).also { println(it) }.get("data").asMap
}


fun Case.toSerie(name: String, value: Long, now: Long): Any {
    return mapOf(
        "metric" to "${getInput("dd_metric_prefix")}.$name",
        "type" to 0,
        "points" to listOf(
            mapOf(
                "timestamp" to now,
                "value" to value
            )
        ),
        "tags" to listOf(
            "class:$clazz",
            "test:$test"
        )
    )
}

fun uploadToDataDog(cases: List<Case>) {
    val now = System.currentTimeMillis()/1000
    val body = mapOf(
        "series" to cases.flatMap {
            listOf(
                it.toSerie("nanos", it.nanos, now),
                it.toSerie("allocs", it.allocs, now)
            )
        }
    )

    val response = body.toJsonElement().toString().let {
        Request.Builder().url("https://api.datadoghq.com/api/v2/series")
            .post(it.toRequestBody("application/json".toMediaType()))
            .addHeader("DD-API-KEY", getInput("DD_API_KEY"))
            .build()
    }.let {
        OkHttpClient.Builder()
            .build()
            .newCall(it)
            .execute()
    }

    check(response.isSuccessful) {
        "Cannot post to Datadog: '${response.code}'\n${response.body?.string()}"
    }
    println("posted to Datadog")
}

fun main() {
    val gcloud = authenticate()
    val testOutput = runTest(gcloud.projectId)
    val testResult = getTestResult(testOutput, gcloud.storage)
    println(testResult)

    if (getOptionalInput("github_token") != null) {
        updateOrCreateGithubIssue(testResult)
    }
    if (getOptionalInput("dd_api_key") != null) {
        uploadToDataDog(testResult.cases)
    }
}

//main()


"""
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=1
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=
    com.apollographql.apollo3.benchmark.Benchmark:
    INSTRUMENTATION_STATUS: test=apollo
    INSTRUMENTATION_STATUS_CODE: 1
    INSTRUMENTATION_STATUS: additionalTestOutputFile_perfetto_trace=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test/Benchmark_apollo_2022-06-29-14-17-50.perfetto-trace
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: allocation_count_median=21699.0
    INSTRUMENTATION_STATUS: allocation_count_min=21699.0
    INSTRUMENTATION_STATUS: allocation_count_stddev=0.14142135623718088
    INSTRUMENTATION_STATUS: android.studio.display.benchmark=    6,722,302   ns       21699 allocs    Benchmark.apollo
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark=    6,722,302   ns       21699 allocs    [trace](file://Benchmark_apollo_2022-06-29-14-17-50.perfetto-trace)    Benchmark.apollo
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark.outputDirPath=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test
    INSTRUMENTATION_STATUS: time_nanos_median=7403769.050000001
    INSTRUMENTATION_STATUS: time_nanos_min=6722302.7
    INSTRUMENTATION_STATUS: time_nanos_stddev=1592766.9878372997
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=1
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=.
    INSTRUMENTATION_STATUS: test=apollo
    INSTRUMENTATION_STATUS_CODE: 0
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=2
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=
    INSTRUMENTATION_STATUS: test=apolloBatchCacheMemory
    INSTRUMENTATION_STATUS_CODE: 1
    INSTRUMENTATION_STATUS: additionalTestOutputFile_perfetto_trace=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test/Benchmark_apolloBatchCacheMemory_2022-06-29-14-18-04.perfetto-trace
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: allocation_count_median=116043.0
    INSTRUMENTATION_STATUS: allocation_count_min=116042.76923076923
    INSTRUMENTATION_STATUS: allocation_count_stddev=0.2134528757739955
    INSTRUMENTATION_STATUS: android.studio.display.benchmark=    6,709,976   ns      116043 allocs    Benchmark.apolloBatchCacheMemory
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark=    6,709,976   ns      116043 allocs    [trace](file://Benchmark_apolloBatchCacheMemory_2022-06-29-14-18-04.perfetto-trace)    Benchmark.apolloBatchCacheMemory
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark.outputDirPath=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test
    INSTRUMENTATION_STATUS: time_nanos_median=8011455.153846154
    INSTRUMENTATION_STATUS: time_nanos_min=6709976.615384615
    INSTRUMENTATION_STATUS: time_nanos_stddev=677401.6930926058
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=2
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=.
    INSTRUMENTATION_STATUS: test=apolloBatchCacheMemory
    INSTRUMENTATION_STATUS_CODE: 0
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=3
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=
    INSTRUMENTATION_STATUS: test=moshi
    INSTRUMENTATION_STATUS_CODE: 1
    INSTRUMENTATION_STATUS: additionalTestOutputFile_perfetto_trace=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test/Benchmark_moshi_2022-06-29-14-18-19.perfetto-trace
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: allocation_count_median=13855.1875
    INSTRUMENTATION_STATUS: allocation_count_min=13855.0
    INSTRUMENTATION_STATUS: allocation_count_stddev=0.11692679333668567
    INSTRUMENTATION_STATUS: android.studio.display.benchmark=    5,914,066   ns       13855 allocs    Benchmark.moshi
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark=    5,914,066   ns       13855 allocs    [trace](file://Benchmark_moshi_2022-06-29-14-18-19.perfetto-trace)    Benchmark.moshi
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark.outputDirPath=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test
    INSTRUMENTATION_STATUS: time_nanos_median=6227601.65625
    INSTRUMENTATION_STATUS: time_nanos_min=5914066.375
    INSTRUMENTATION_STATUS: time_nanos_stddev=289708.61185970454
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=3
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=.
    INSTRUMENTATION_STATUS: test=moshi
    INSTRUMENTATION_STATUS_CODE: 0
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=4
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=
    INSTRUMENTATION_STATUS: test=apolloParseAndNormalize
    INSTRUMENTATION_STATUS_CODE: 1
    INSTRUMENTATION_STATUS: additionalTestOutputFile_perfetto_trace=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test/Benchmark_apolloParseAndNormalize_2022-06-29-14-18-33.perfetto-trace
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: allocation_count_median=115688.0
    INSTRUMENTATION_STATUS: allocation_count_min=115688.0
    INSTRUMENTATION_STATUS: allocation_count_stddev=0.27386127875258304
    INSTRUMENTATION_STATUS: android.studio.display.benchmark=   12,853,595   ns      115688 allocs    Benchmark.apolloParseAndNormalize
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark=   12,853,595   ns      115688 allocs    [trace](file://Benchmark_apolloParseAndNormalize_2022-06-29-14-18-33.perfetto-trace)    Benchmark.apolloParseAndNormalize
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark.outputDirPath=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test
    INSTRUMENTATION_STATUS: time_nanos_median=1.4627983083333332E7
    INSTRUMENTATION_STATUS: time_nanos_min=1.2853595166666666E7
    INSTRUMENTATION_STATUS: time_nanos_stddev=1177040.7696624359
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=4
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=.
    INSTRUMENTATION_STATUS: test=apolloParseAndNormalize
    INSTRUMENTATION_STATUS_CODE: 0
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=5
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=
    INSTRUMENTATION_STATUS: test=apolloBatchCacheSql
    INSTRUMENTATION_STATUS_CODE: 1
    INSTRUMENTATION_STATUS: additionalTestOutputFile_perfetto_trace=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test/Benchmark_apolloBatchCacheSql_2022-06-29-14-18-45.perfetto-trace
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: allocation_count_median=218633.0
    INSTRUMENTATION_STATUS: allocation_count_min=218633.0
    INSTRUMENTATION_STATUS: allocation_count_stddev=1.6431676725154984
    INSTRUMENTATION_STATUS: android.studio.display.benchmark=   53,129,224   ns      218633 allocs    Benchmark.apolloBatchCacheSql
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark=   53,129,224   ns      218633 allocs    [trace](file://Benchmark_apolloBatchCacheSql_2022-06-29-14-18-45.perfetto-trace)    Benchmark.apolloBatchCacheSql
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark.outputDirPath=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test
    INSTRUMENTATION_STATUS: time_nanos_median=5.8039615E7
    INSTRUMENTATION_STATUS: time_nanos_min=5.3129224E7
    INSTRUMENTATION_STATUS: time_nanos_stddev=4079558.0775523763
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Benchmark
    INSTRUMENTATION_STATUS: current=5
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=.
    INSTRUMENTATION_STATUS: test=apolloBatchCacheSql
    INSTRUMENTATION_STATUS_CODE: 0
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Calendar
    INSTRUMENTATION_STATUS: current=6
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=
    com.apollographql.apollo3.benchmark.Calendar:
    INSTRUMENTATION_STATUS: test=readFromCacheOperationBased
    INSTRUMENTATION_STATUS_CODE: 1
    INSTRUMENTATION_STATUS: additionalTestOutputFile_perfetto_trace=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test/Calendar_readFromCacheOperationBased_2022-06-29-14-18-58.perfetto-trace
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: allocation_count_median=672414.0
    INSTRUMENTATION_STATUS: allocation_count_min=672414.0
    INSTRUMENTATION_STATUS: allocation_count_stddev=1.4142135623730951
    INSTRUMENTATION_STATUS: android.studio.display.benchmark=   55,116,411   ns      672414 allocs    Calendar.readFromCacheOperationBased
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark=   55,116,411   ns      672414 allocs    [trace](file://Calendar_readFromCacheOperationBased_2022-06-29-14-18-58.perfetto-trace)    Calendar.readFromCacheOperationBased
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark.outputDirPath=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test
    INSTRUMENTATION_STATUS: time_nanos_median=5.6223469E7
    INSTRUMENTATION_STATUS: time_nanos_min=5.5116411E7
    INSTRUMENTATION_STATUS: time_nanos_stddev=9626054.557676245
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Calendar
    INSTRUMENTATION_STATUS: current=6
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=.
    INSTRUMENTATION_STATUS: test=readFromCacheOperationBased
    INSTRUMENTATION_STATUS_CODE: 0
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Calendar
    INSTRUMENTATION_STATUS: current=7
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=
    INSTRUMENTATION_STATUS: test=readFromCacheResponseBased
    INSTRUMENTATION_STATUS_CODE: 1
    INSTRUMENTATION_STATUS: additionalTestOutputFile_perfetto_trace=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test/Calendar_readFromCacheResponseBased_2022-06-29-14-19-11.perfetto-trace
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: allocation_count_median=654779.0
    INSTRUMENTATION_STATUS: allocation_count_min=654779.0
    INSTRUMENTATION_STATUS: allocation_count_stddev=1.7320508075688772
    INSTRUMENTATION_STATUS: android.studio.display.benchmark=   51,580,005   ns      654779 allocs    Calendar.readFromCacheResponseBased
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark=   51,580,005   ns      654779 allocs    [trace](file://Calendar_readFromCacheResponseBased_2022-06-29-14-19-11.perfetto-trace)    Calendar.readFromCacheResponseBased
    INSTRUMENTATION_STATUS: android.studio.v2display.benchmark.outputDirPath=/storage/emulated/0/Android/media/com.apollographql.apollo3.benchmark.test
    INSTRUMENTATION_STATUS: time_nanos_median=5.3266802E7
    INSTRUMENTATION_STATUS: time_nanos_min=5.1580005E7
    INSTRUMENTATION_STATUS: time_nanos_stddev=1.1872718565217752E7
    INSTRUMENTATION_STATUS_CODE: 2
    INSTRUMENTATION_STATUS: class=com.apollographql.apollo3.benchmark.Calendar
    INSTRUMENTATION_STATUS: current=7
    INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
    INSTRUMENTATION_STATUS: numtests=7
    INSTRUMENTATION_STATUS: stream=.
    INSTRUMENTATION_STATUS: test=readFromCacheResponseBased
    INSTRUMENTATION_STATUS_CODE: 0
    INSTRUMENTATION_RESULT: stream=

    Time: 95.336

    OK (7 tests)


    INSTRUMENTATION_CODE: -1
""".trimIndent().parseCases().let {
    uploadToDataDog(it)
}