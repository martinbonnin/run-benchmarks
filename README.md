# Run Benchmarks ⏱️

A Github Action that runs
your [Android microbenchmarks](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview)
in [Firebase Test Labs](https://firebase.google.com/docs/test-lab) and optionally:
- updates a GitHub issue with the latest benchmark results ([see here for a sample issue](https://github.com/martinbonnin/run-benchmarks-sample/issues/1))
- publishes the metrics to Datadog ([see here for a sample dashboard](https://p.datadoghq.com/sb/5218edc4-01bd-11ed-a9be-da7ad0900002-8b732d527dbbc83641c63ef56364d8d1))

See the action in action (ha ha!) at https://github.com/martinbonnin/run-benchmarks-sample

> Note: Macrobenchmarks are not supported just yet

### Configuration

```yaml
on:
  schedule:
    # Run every night
    - cron: '0 3 * * *'

jobs:
  benchmarks:
    runs-on: ubuntu-20.04
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '11'
      - uses: gradle/gradle-build-action@v2.1.4
      - run: |
          # Build the benchmark apks
          ./gradlew :microbenchmark:packageReleaseAndroidTest :app:assembleRelease 
      - name: microbenchmarks
        uses: martinbonnin/run-benchmarks@main
        with:
          google_services_json: ${{ secrets.GOOGLE_SERVICES_JSON }}
          
          app_apk: 'app/build/outputs/apk/release/app-release.apk'
          test_apk: 'microbenchmark/build/outputs/apk/androidTest/release/microbenchmark-release-androidTest.apk'
          device_model: 'redfin,locale=en,orientation=portrait' 
          
          # Optional, upload to datadog
          dd_api_key: ${{ secrets.DD_API_KEY }}
          dd_metric_prefix: 'android.benchmark'

          # Optional, create a dashboard issue that publishes the latest results
          github_token: ${{ github.token }}
          dd_dashboard_url: 'https://p.datadoghq.com/sb/'
```

### Datadog integration

If you specify `dd_api_key`, the metrics are uploaded to Datadog automatically. This action publishes 2 metrics:

- ${dd_metric_prefix}.allocs: the median number of allocations per test
- ${dd_metric_prefix}.nanos: the median number of nanosecond per test

Each metric is tagged with the class of the test and the name of the test. For an example:

- `class:com.example.BenchmarkTest`
- `test:parseSomething`
