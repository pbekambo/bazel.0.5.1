// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

/**
 * A simple interface for storing blobs (in the form of byte arrays) each one indexed by a
 * hexadecimal string. Implementation must be thread-safe.
 */
public interface SimpleBlobStore {
  /** Returns true if the provided {@param key} is stored in the blob store. */
  boolean containsKey(String key);

  /**
   * Returns the blob (in the form of a byte array) indexed by {@param key}. Returns null if the
   * {@pram key} cannot be found.
   */
  byte[] get(String key);

  /**
   * Uploads a blob (as {@param value}) indexed by {@param key} to the blob store. Existing blob
   * indexed by the same {@param key} will be overwritten.
   */
  void put(String key, byte[] value);
}
