# Vendored KleidiAI v1.31.0 (Apache-2.0)

Used by llama.cpp's ggml-cpu (`-DGGML_CPU_KLEIDIAI=ON`) via `FETCHCONTENT_SOURCE_DIR_KLEIDIAI`, so the
Android/host native builds never download anything. Pruned to what that build needs: `experimental/`
(only built with KLEIDIAI_BUILD_EXPERIMENTAL_OPS, default OFF), `examples/`, `docker/`, `tools/`, CI files and the
googletest/benchmark zips (tests/benchmarks are forced OFF) were removed.
