cmake_minimum_required(VERSION 3.25)
project(gapless LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Every component, as one library shared by the demo and the tests.
# Includes are written from src/, e.g. #include "handler/feed_handler.hpp".
add_library(gapless
  src/book/order_book.cpp
  src/exchange/exchange.cpp
  src/network/lossy_link.cpp
  src/handler/feed_handler.cpp
  src/lamport/lamport.cpp)
target_include_directories(gapless PUBLIC src)
target_compile_options(gapless PUBLIC -Wall -Wextra -Wpedantic)

add_executable(gapless_demo apps/demo.cpp)
target_link_libraries(gapless_demo PRIVATE gapless)

# GoogleTest, fetched at configure time and pinned by its hash.
include(FetchContent)
FetchContent_Declare(
  googletest
  URL https://github.com/google/googletest/archive/refs/tags/v1.17.0.tar.gz
  URL_HASH SHA256=65fab701d9829d38cb77c14acdc431d2108bfdbf8979e40eb8ae567edf10b27c
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
  SYSTEM)
set(BUILD_GMOCK OFF CACHE BOOL "" FORCE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()
include(GoogleTest)
add_executable(gapless_tests
  tests/order_book_test.cpp
  tests/feed_handler_test.cpp
  tests/lamport_test.cpp)
target_link_libraries(gapless_tests PRIVATE gapless GTest::gtest_main)
gtest_discover_tests(gapless_tests)
