cmake_minimum_required(VERSION 3.16)
project(melox_ja_g2p CXX C)

# MeCab (vendored) still uses the `register` keyword, removed in C++17.
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenJTalk G2P frontend. Vendored from r9y9/open_jtalk v1.11.1 (Modified BSD,
# see COPYING.open_jtalk / COPYING.mecab). No HTS engine and no HTS voice: only
# text2mecab → MeCab + NAIST dictionary → NJD sets → JPCommon labels.
set(OPEN_JTALK_SRC "${CMAKE_CURRENT_SOURCE_DIR}/third_party/open_jtalk")

# MeCab's sources include "config.h" from their own directory, exactly like
# open_jtalk's own build produces it.
file(WRITE "${OPEN_JTALK_SRC}/mecab/src/config.h"
"/* generated for the MeloX G2P build */
#define HAVE_CTYPE_H 1
#define HAVE_DIRENT_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_MEMORY_H 1
#define HAVE_SETJMP_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_MMAN_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIMES_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_GETENV 1
#define HAVE_GETPAGESIZE 1
#define HAVE_MMAP 1
#define HAVE_OPENDIR 1
#define HAVE_SETJMP 1
#define HAVE_SQRT 1
#define HAVE_STRSTR 1
#define SIZEOF_CHAR 1
#define SIZEOF_INT 4
#define SIZEOF_LONG 8
#define SIZEOF_LONG_LONG 8
#define SIZEOF_SHORT 2
#define SIZEOF_SIZE_T 8
#define MECAB_CHARSET utf-8
")

foreach(dir jpcommon mecab/src mecab2njd njd njd2jpcommon
        njd_set_accent_phrase njd_set_accent_type njd_set_digit
        njd_set_long_vowel njd_set_pronunciation njd_set_unvoiced_vowel text2mecab)
    aux_source_directory("${OPEN_JTALK_SRC}/${dir}" MELOX_SOURCES)
    include_directories("${OPEN_JTALK_SRC}/${dir}")
endforeach()

add_definitions(-DHAVE_CONFIG_H
                -DDIC_VERSION=102
                -DMECAB_DEFAULT_RC="dummy"
                -DMECAB_WITHOUT_SHARE_DIC
                -DPACKAGE="open_jtalk"
                -DVERSION="1.11"
                -DCHARSET_UTF_8
                -DMECAB_CHARSET=utf-8
                -DMECAB_UTF8_USE_ONLY)

add_library(melox_ja_g2p SHARED ja_g2p.cpp ja_g2p_jni.cpp ${MELOX_SOURCES})
target_include_directories(melox_ja_g2p PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

if(ANDROID)
    target_link_libraries(melox_ja_g2p log)
    # Keep the library name stable so Kotlin's System.loadLibrary matches.
    set_target_properties(melox_ja_g2p PROPERTIES OUTPUT_NAME "melox_ja_g2p")
else()
    # Host build: same shared library plus a CLI, so the JVM tests can load the
    # very same code path that ships in the APK.
    find_package(JNI REQUIRED)
    target_include_directories(melox_ja_g2p PRIVATE ${JNI_INCLUDE_DIRS})
    add_executable(ja_g2p_tool host_main.cpp)
    target_link_libraries(ja_g2p_tool melox_ja_g2p)
endif()
