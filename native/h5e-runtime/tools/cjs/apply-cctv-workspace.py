"""Trial: retain the bounded H5E scratch allocation within a segment.

Apply to a TvWasm/cjs checkout. Keep this experimental until encrypted stream
regressions pass. Thread-local scratch is invalidated at every module reset;
the existing 2 MiB safety tail and all wasm bounds checks are retained.
"""
import argparse
from pathlib import Path


def patch(source: str) -> str:
    if "g_nal_workspace_capacity" in source:
        return source
    old = "static __thread int g_runtime_initialized;"
    if old not in source:
        raise ValueError("Expected thread-local H5E runtime")
    source = source.replace(old, old + "\nstatic __thread u32 g_nal_workspace;\nstatic __thread u32 g_nal_workspace_capacity;")
    source = source.replace("static int reset_module_state(void) {", "static int reset_module_state(void) {\n  g_nal_workspace = 0;\n  g_nal_workspace_capacity = 0;")
    start = source.index("static int decrypt_nal(")
    end = source.index("static int append_bytes(", start)
    block = source[start:end]
    old = "  u32 data_address = w2c_cctv__h5e_Da(&g_module,\n      (u32) nal_length + (u32) host_length + NAL_MEMORY_EXTEND);"
    new = """  u32 required_capacity = (u32) nal_length + (u32) host_length + NAL_MEMORY_EXTEND;
  if (g_nal_workspace_capacity < required_capacity) {
    if (g_nal_workspace != 0) {
      w2c_cctv__h5e_Ca(&g_module, g_nal_workspace);
      g_nal_workspace = 0;
      g_nal_workspace_capacity = 0;
    }
    g_nal_workspace = w2c_cctv__h5e_Da(&g_module, required_capacity);
    if (g_nal_workspace != 0) g_nal_workspace_capacity = required_capacity;
  }
  u32 data_address = g_nal_workspace;"""
    if old not in block:
        raise ValueError("H5E allocation changed; refusing partial patch")
    block = block.replace(old, new)
    # Failure paths still free and invalidate the allocation.
    block = block.replace("    w2c_cctv__h5e_Ca(&g_module, data_address);", "    w2c_cctv__h5e_Ca(&g_module, data_address);\n    g_nal_workspace = 0;\n    g_nal_workspace_capacity = 0;")
    old = "  w2c_cctv__h5e_Ca(&g_module, data_address);\n  g_wasm_stage = \"idle\";\n  return 1;"
    if old not in block:
        raise ValueError("H5E success path changed")
    block = block.replace(old, "  g_wasm_stage = \"idle\";\n  return 1;")
    source = source[:start] + block + source[end:]
    source = source.replace("  g_runtime_initialized = 0;", "  g_runtime_initialized = 0;\n  g_nal_workspace = 0;\n  g_nal_workspace_capacity = 0;")
    return source


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    args = parser.parse_args()
    path = args.checkout / "sites/tv.cctv.com/native/cctv_h5e_decryptor.c"
    source = path.read_text(encoding="utf-8")
    updated = patch(source)
    if updated != source:
        path.write_text(updated, encoding="utf-8", newline="\n")
    print("CCTV workspace trial ready:", path)
