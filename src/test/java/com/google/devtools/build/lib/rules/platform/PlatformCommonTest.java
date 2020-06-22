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

package com.google.devtools.build.lib.rules.platform;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.skylark.util.SkylarkTestCase;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PlatformCommon}. */
@RunWith(JUnit4.class)
public class PlatformCommonTest extends SkylarkTestCase {

  @Test
  public void testCreateToolchain() throws Exception {
    scratch.file(
        "test/toolchain.bzl",
        "def _impl(ctx):",
        "    return platform_common.toolchain(",
        "        exec_compatible_with = ctx.attr.exec_compatible_with,",
        "        target_compatible_with = ctx.attr.target_compatible_with,",
        "        extra_label = ctx.attr.extra_label,",
        "        extra_str = ctx.attr.extra_str,",
        "    )",
        "test_toolchain = rule(",
        "    implementation = _impl,",
        "    attrs = {",
        "       'runs': attr.label_list(allow_files=True),",
        "       'exec_compatible_with': attr.label_list(",
        "           providers = [platform_common.ConstraintValueInfo]),",
        "       'target_compatible_with': attr.label_list(",
        "           providers = [platform_common.ConstraintValueInfo]),",
        "       'extra_label': attr.label(),",
        "       'extra_str': attr.string(),",
        "    }",
        ")");
    scratch.file(
        "test/BUILD",
        "load(':toolchain.bzl', 'test_toolchain')",
        "constraint_setting(name = 'os')",
        "constraint_value(name = 'linux',",
        "    constraint_setting = ':os')",
        "constraint_value(name = 'mac',",
        "    constraint_setting = ':os')",
        "filegroup(name = 'dep_rule')",
        "test_toolchain(",
        "    name = 'linux_toolchain',",
        "    exec_compatible_with = [",
        "      ':linux',",
        "    ],",
        "    target_compatible_with = [",
        "      ':mac',",
        "    ],",
        "    extra_label = ':dep_rule',",
        "    extra_str = 'bar',",
        ")");
    TransitiveInfoCollection toolchainProvider = getConfiguredTarget("//test:linux_toolchain");
    assertThat(toolchainProvider).isNotNull();

    assertThat((List<?>) toolchainProvider.get("exec_compatible_with"))
        .containsExactly(
            ConstraintValueInfo.create(
                ConstraintSettingInfo.create(makeLabel("//test:os")), makeLabel("//test:linux")));
    assertThat((List<?>) toolchainProvider.get("target_compatible_with"))
        .containsExactly(
            ConstraintValueInfo.create(
                ConstraintSettingInfo.create(makeLabel("//test:os")), makeLabel("//test:mac")));

    assertThat(((ConfiguredTarget) toolchainProvider.get("extra_label")).getLabel())
        .isEqualTo(makeLabel("//test:dep_rule"));
    assertThat(toolchainProvider.get("extra_str")).isEqualTo("bar");
  }
}
