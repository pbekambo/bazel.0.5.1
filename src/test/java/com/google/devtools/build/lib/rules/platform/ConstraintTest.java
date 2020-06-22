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
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link ConstraintSetting} and {@link ConstraintValue}. */
@RunWith(JUnit4.class)
public class ConstraintTest extends BuildViewTestCase {

  @Before
  public void createConstraints() throws Exception {
    scratch.file(
        "constraint/BUILD",
        "constraint_setting(name = 'basic')",
        "constraint_value(name = 'foo',",
        "    constraint_setting = ':basic',",
        "    )",
        "constraint_value(name = 'bar',",
        "    constraint_setting = ':basic',",
        "    )");
  }

  @Test
  public void testConstraint() throws Exception {
    ConfiguredTarget setting = getConfiguredTarget("//constraint:basic");
    assertThat(setting).isNotNull();
    assertThat(ConstraintSetting.constraintSetting(setting)).isNotNull();
    assertThat(ConstraintSetting.constraintSetting(setting)).isNotNull();
    assertThat(ConstraintSetting.constraintSetting(setting).label())
        .isEqualTo(Label.parseAbsolute("//constraint:basic"));
    ConfiguredTarget fooValue = getConfiguredTarget("//constraint:foo");
    assertThat(fooValue).isNotNull();
    assertThat(ConstraintValue.constraintValue(fooValue)).isNotNull();
    assertThat(ConstraintValue.constraintValue(fooValue).constraint().label())
        .isEqualTo(Label.parseAbsolute("//constraint:basic"));
    assertThat(ConstraintValue.constraintValue(fooValue).label())
        .isEqualTo(Label.parseAbsolute("//constraint:foo"));
    ConfiguredTarget barValue = getConfiguredTarget("//constraint:bar");
    assertThat(barValue).isNotNull();
    assertThat(ConstraintValue.constraintValue(barValue).constraint().label())
        .isEqualTo(Label.parseAbsolute("//constraint:basic"));
    assertThat(ConstraintValue.constraintValue(barValue).label())
        .isEqualTo(Label.parseAbsolute("//constraint:bar"));
  }

  @Test
  public void testConstraint_skylark() throws Exception {

    scratch.file(
        "test/platform/constraints.bzl",
        "def _impl(ctx):",
        "  constraint_value = ctx.attr.constraint[platform_common.ConstraintValueInfo]",
        "  return struct(",
        "    setting = constraint_value.constraint.label,",
        "    value = constraint_value.label)",
        "my_rule = rule(",
        "  _impl,",
        "  attrs = { 'constraint': attr.label(providers = [platform_common.ConstraintValueInfo])},",
        ")");

    scratch.file(
        "test/platform/BUILD",
        "load('//test/platform:constraints.bzl', 'my_rule')",
        "my_rule(name = 'r',",
        "  constraint = '//constraint:foo')");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//test/platform:r");
    assertThat(configuredTarget).isNotNull();

    Label settingLabel = (Label) configuredTarget.get("setting");
    assertThat(settingLabel).isNotNull();
    assertThat(settingLabel).isEqualTo(makeLabel("//constraint:basic"));
    Label valueLabel = (Label) configuredTarget.get("value");
    assertThat(valueLabel).isNotNull();
    assertThat(valueLabel).isEqualTo(makeLabel("//constraint:foo"));
  }
}
