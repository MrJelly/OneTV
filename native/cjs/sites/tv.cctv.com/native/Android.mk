LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := cctv
LOCAL_MODULE_FILENAME := cctv
LOCAL_SRC_FILES := cctv_h5e_decryptor.c generated/cctv_h5e_wasm.c wasm-rt/wasm-rt-impl.c wasm-rt/wasm-rt-mem-impl.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/generated $(LOCAL_PATH)/wasm-rt
LOCAL_CFLAGS := -O3 -DNDEBUG -std=c99 -DWASM_RT_USE_MMAP=0 -fvisibility=hidden
LOCAL_LDLIBS := -llog -lm
include $(BUILD_SHARED_LIBRARY)
