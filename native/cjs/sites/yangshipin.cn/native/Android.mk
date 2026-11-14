LOCAL_PATH := $(call my-dir)

# Compile CMG's older wasm runtime separately with a symbol prefix, then link it
# and YSP signing into ONE site library. No cross-site native dependency.
include $(CLEAR_VARS)
LOCAL_MODULE := cmg_internal
LOCAL_SRC_FILES := cmg_decryptor.c generated_cmg/cmg_wasm.c cmg-rt/wasm-rt-impl.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/generated_cmg $(LOCAL_PATH)/cmg-rt
LOCAL_CFLAGS := -O3 -DNDEBUG -std=c99 -fvisibility=hidden -DWASM_RT_MEMCHECK_SIGNAL_HANDLER=0 -Dinit=cmg_module_init \
 -Dwasm_rt_allocate_memory=cmg_wasm_rt_allocate_memory \
 -Dwasm_rt_allocate_table=cmg_wasm_rt_allocate_table \
 -Dwasm_rt_call_stack_depth=cmg_wasm_rt_call_stack_depth \
 -Dwasm_rt_grow_memory=cmg_wasm_rt_grow_memory \
 -Dwasm_rt_register_func_type=cmg_wasm_rt_register_func_type \
 -Dwasm_rt_trap=cmg_wasm_rt_trap \
 -Dg_jmp_buf=cmg_wasm_g_jmp_buf \
 -Dg_saved_call_stack_depth=cmg_wasm_g_saved_call_stack_depth \
 -Dg_func_types=cmg_wasm_g_func_types \
 -Dg_func_type_count=cmg_wasm_g_func_type_count
include $(BUILD_STATIC_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := yangshipin
LOCAL_MODULE_FILENAME := yangshipin
LOCAL_SRC_FILES := ysp_keygen.c generated_ysp/ysp_keygen_wasm.c ysp-rt/wasm-rt-impl.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/generated_ysp $(LOCAL_PATH)/ysp-rt
LOCAL_CFLAGS := -O3 -DNDEBUG -std=c99 -DWASM_RT_USE_MMAP=0 -fvisibility=hidden
LOCAL_WHOLE_STATIC_LIBRARIES := cmg_internal
LOCAL_LDLIBS := -llog -lm
include $(BUILD_SHARED_LIBRARY)
