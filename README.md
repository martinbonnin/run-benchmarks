# Run Benchmarks ⏱️

A Github Action that runs
your [Android micro and macrobenchmarks](https://developer.android.com/topic/performance/benchmarking/benchmarking-overview)
in [Firebase Test Labs](https://firebase.google.com/docs/test-lab)


### Configuration

```yaml
name: Run Benchmarks
# Run benchmarks every night at midnight
on:
  schedule:
    - cron: "0 0 * * *"

jobs:
  update:
    runs-on: ubuntu-20.04
    steps:
      - uses: actions/checkout@v2
      - uses: martinbonnin/run-benchmarks@main
        with:
```

