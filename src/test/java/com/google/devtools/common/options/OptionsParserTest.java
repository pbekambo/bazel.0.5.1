// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.common.options;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.common.options.OptionsParser.newOptionsParser;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.OptionsParser.ConstructionException;
import com.google.devtools.common.options.OptionsParser.OptionUsageRestrictions;
import com.google.devtools.common.options.OptionsParser.OptionValueDescription;
import com.google.devtools.common.options.OptionsParser.UnparsedOptionValueDescription;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests {@link OptionsParser}.
 */
@RunWith(JUnit4.class)
public class OptionsParserTest {

  /** Dummy comment (linter suppression) */
  public static class BadOptions extends OptionsBase {
    @Option(
      name = "foo",
      defaultValue = "false"
    )
    public boolean foo1;

    @Option(
      name = "foo",
      defaultValue = "false"
    )
    public boolean foo2;
  }

  @Test
  public void errorsDuringConstructionAreWrapped() {
    try {
      newOptionsParser(BadOptions.class);
      fail();
    } catch (OptionsParser.ConstructionException e) {
      assertThat(e.getCause()).isInstanceOf(DuplicateOptionDeclarationException.class);
    }
  }

  public static class ExampleFoo extends OptionsBase {

    @Option(name = "foo",
            category = "one",
            defaultValue = "defaultFoo")
    public String foo;

    @Option(name = "bar",
            category = "two",
            defaultValue = "42")
    public int bar;

    @Option(name = "bing",
            category = "one",
            defaultValue = "",
            allowMultiple = true)
    public List<String> bing;

    @Option(name = "bang",
            category = "one",
            defaultValue = "",
            converter = StringConverter.class,
            allowMultiple = true)
    public List<String> bang;

    @Option(
      name = "nodoc",
      optionUsageRestrictions = OptionUsageRestrictions.UNDOCUMENTED,
      defaultValue = "",
      allowMultiple = false
    )
    public String nodoc;
  }

  public static class ExampleBaz extends OptionsBase {

    @Option(name = "baz",
            category = "one",
            defaultValue = "defaultBaz")
    public String baz;
  }

  /** Subclass of an options class. */
  public static class ExampleBazSubclass extends ExampleBaz {

    @Option(name = "baz_subclass",
            category = "one",
            defaultValue = "defaultBazSubclass")
    public String bazSubclass;
  }

  /**
   * Example with empty to null string converter
   */
  public static class ExampleBoom extends OptionsBase {
    @Option(name = "boom",
            defaultValue = "defaultBoom",
            converter = EmptyToNullStringConverter.class)
    public String boom;
  }

  /**
   * Example with internal options
   */
  public static class ExampleInternalOptions extends OptionsBase {
    @Option(
      name = "internal_boolean",
      optionUsageRestrictions = OptionUsageRestrictions.INTERNAL,
      defaultValue = "true"
    )
    public boolean privateBoolean;

    @Option(
      name = "internal_string",
      optionUsageRestrictions = OptionUsageRestrictions.INTERNAL,
      defaultValue = "super secret"
    )
    public String privateString;

    @Option(
      name = "public string",
      optionUsageRestrictions = OptionUsageRestrictions.UNDOCUMENTED,
      defaultValue = "not a secret"
    )
    public String publicString;
  }

  public static class StringConverter implements Converter<String> {
    @Override
    public String convert(String input) {
      return input;
    }
    @Override
    public String getTypeDescription() {
      return "a string";
    }
  }

  /**
   * A converter that defaults to null if the input is the empty string
   */
  public static class EmptyToNullStringConverter extends StringConverter {
    @Override
    public String convert(String input) {
      return input.isEmpty() ? null : input;
    }
  }

  @Test
  public void parseWithMultipleOptionsInterfaces()
      throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.parse("--baz=oops", "--bar", "17");
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("defaultFoo", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("oops", baz.baz);
  }
  
  @Test
  public void parseWithParamsFile() throws OptionsParsingException, IOException {
    // TODO(bazel-team): Switch to an in memory file system, here and below.
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--baz=oops --bar 17"),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("defaultFoo", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("oops", baz.baz);
  }
  
  @Test
  public void parseWithEmptyParamsFile() throws OptionsParsingException, IOException {
    // TODO(bazel-team): Switch to an in memory file system, here and below.
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of(""),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("defaultFoo", foo.foo);
    assertEquals(42, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("defaultBaz", baz.baz);
  }
  
  @Test
  public void parseWithParamsFileWithEmptyStringValues() throws Exception {
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--baz", "", "--foo", ""),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("", foo.foo);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("", baz.baz);
  }
  
  @Test
  public void parseWithParamsFileWithEmptyString() throws OptionsParsingException, IOException {
    // TODO(bazel-team): Switch to an in memory file system, here and below.
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--baz  --bar 17"),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("defaultFoo", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("", baz.baz);
  }
  
  @Test
  public void parseWithParamsFileWithEmptyStringAtEnd()
      throws OptionsParsingException, IOException {
    // TODO(bazel-team): Switch to an in memory file system, here and below.
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--bar",
            "17",
            " --baz",
            ""),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("defaultFoo", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("", baz.baz);
  }

  @Test
  public void parseWithParamsFileWithQuotedSpaces() throws OptionsParsingException, IOException {
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--foo=\"fuzzy\nfoo\" --bar 17"),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("\"fuzzy\nfoo\"", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("defaultBaz", baz.baz);
  }

  @Test
  public void parseWithParamsFileWithEscapedSpaces() throws OptionsParsingException, IOException {
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--foo=fuzzy\\ foo --bar 17"),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("fuzzy\\ foo", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("defaultBaz", baz.baz);
  }

  @Test
  public void parseWithParamsFileWithEscapedQuotes() throws OptionsParsingException, IOException {
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--foo=\"fuzzy\\\"foo\" --bar 17"),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("\"fuzzy\\\"foo\"", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("defaultBaz", baz.baz);
  }

  @Test
  public void parseWithParamsFileUnmatchedQuote() throws IOException {
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--foo=\"fuzzy foo --bar 17"),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    try {
      parser.parse("@" + params);
      fail();
    } catch (OptionsParsingException e) {
      assertEquals(
          String.format(
              ParamsFilePreProcessor.ERROR_MESSAGE_FORMAT,
              params,
              String.format(ParamsFilePreProcessor.UNFINISHED_QUOTE_MESSAGE_FORMAT, "\"", 6)),
          e.getMessage());
    }
  }
  
  @Test
  public void parseWithParamsFileWithMultilineStringValues() throws Exception {
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of(
            "--baz",
            "'hello\nworld'",
            "--foo",
            "hello\\",
            "world",
            "--nodoc",
            "\"hello",
            "world\""),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("hello\\\nworld", foo.foo);
    assertEquals("\"hello\nworld\"", foo.nodoc);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("'hello\nworld'", baz.baz);
  }
  
  @Test
  public void parseWithParamsFileWithMultilineStringValuesCRLF() throws Exception {
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of(
            "--baz\r\n'hello\nworld'\r\n--foo\r\nhello\\\r\nworld\r\n\r\n"
            + "--nodoc\r\n\"hello\r\nworld\""),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("'hello\nworld'", baz.baz);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("hello\\\nworld", foo.foo);
    assertEquals("\"hello\nworld\"", foo.nodoc);
  }

  @Test
  public void parseWithParamsFileMultiline() throws OptionsParsingException, IOException {
    // TODO(bazel-team): Switch to an in memory file system.
    Path params = Files.createTempDirectory("foo").resolve("params");
    Files.write(
        params,
        ImmutableList.of("--baz", "oops", "--bar", "17"),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE);

    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    parser.parse("@" + params);
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("defaultFoo", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("oops", baz.baz);
  }

  @Test
  public void parsingFailsWithMissingParamsFile() {
    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.enableParamsFileSupport(FileSystems.getDefault());
    List<String> unknownOpts = asList("@does/not/exist");
    try {
      parser.parse(unknownOpts);
      fail();
    } catch (OptionsParsingException e) {
      assertEquals("@does/not/exist", e.getInvalidArgument());
      assertNotNull(parser.getOptions(ExampleFoo.class));
      assertNotNull(parser.getOptions(ExampleBaz.class));
    }
  }

  @Test
  public void parseWithOptionsInheritance() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleBazSubclass.class);
    parser.parse("--baz_subclass=cat", "--baz=dog");
    ExampleBazSubclass subclassOptions = parser.getOptions(ExampleBazSubclass.class);
    assertThat(subclassOptions.bazSubclass).isEqualTo("cat");
    assertThat(subclassOptions.baz).isEqualTo("dog");
    ExampleBaz options = parser.getOptions(ExampleBaz.class);
    // This is a test showcasing the lack of functionality for retrieving parsed options at a
    // superclass type class type. If there's a need for this functionality, we can add it later.
    assertThat(options).isNull();
  }

  @Test
  public void parserWithUnknownOption() {
    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    try {
      parser.parse("--unknown", "option");
      fail();
    } catch (OptionsParsingException e) {
      assertEquals("--unknown", e.getInvalidArgument());
      assertEquals("Unrecognized option: --unknown", e.getMessage());
    }
    assertEquals(Collections.<String>emptyList(), parser.getResidue());
  }

  @Test
  public void parserWithSingleDashOption() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    try {
      parser.parse("-baz=oops", "-bar", "17");
      fail();
    } catch (OptionsParsingException expected) {}

    parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.setAllowSingleDashLongOptions(true);
    parser.parse("-baz=oops", "-bar", "17");
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("defaultFoo", foo.foo);
    assertEquals(17, foo.bar);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("oops", baz.baz);
  }

  @Test
  public void parsingFailsWithUnknownOptions() {
    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    List<String> unknownOpts = asList("--unknown", "option", "--more_unknowns");
    try {
      parser.parse(unknownOpts);
      fail();
    } catch (OptionsParsingException e) {
      assertEquals("--unknown", e.getInvalidArgument());
      assertEquals("Unrecognized option: --unknown", e.getMessage());
      assertNotNull(parser.getOptions(ExampleFoo.class));
      assertNotNull(parser.getOptions(ExampleBaz.class));
    }
  }

  @Test
  public void parsingFailsWithInternalBooleanOptionAsIfUnknown() {
    OptionsParser parser = newOptionsParser(ExampleInternalOptions.class);
    List<String> internalOpts = asList("--internal_boolean");
    try {
      parser.parse(internalOpts);
      fail();
    } catch (OptionsParsingException e) {
      assertEquals("--internal_boolean", e.getInvalidArgument());
      assertEquals("Unrecognized option: --internal_boolean", e.getMessage());
      assertNotNull(parser.getOptions(ExampleInternalOptions.class));
    }
  }

  @Test
  public void parsingFailsWithNegatedInternalBooleanOptionAsIfUnknown() {
    OptionsParser parser = newOptionsParser(ExampleInternalOptions.class);
    List<String> internalOpts = asList("--nointernal_boolean");
    try {
      parser.parse(internalOpts);
      fail();
    } catch (OptionsParsingException e) {
      assertEquals("--nointernal_boolean", e.getInvalidArgument());
      assertEquals("Unrecognized option: --nointernal_boolean", e.getMessage());
      assertNotNull(parser.getOptions(ExampleInternalOptions.class));
    }
  }

  @Test
  public void parsingSucceedsWithSpacesInFlagName() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleInternalOptions.class);
    List<String> spacedOpts = asList("--public string=value with spaces");
    parser.parse(spacedOpts);
    assertEquals(parser.getOptions(ExampleInternalOptions.class).publicString, "value with spaces");
  }

  @Test
  public void parsingFailsForInternalOptionWithValueInSameArgAsIfUnknown() {
    OptionsParser parser = newOptionsParser(ExampleInternalOptions.class);
    List<String> internalOpts = asList("--internal_string=any_value");
    try {
      parser.parse(internalOpts);
      fail("parsing should have failed for including a private option");
    } catch (OptionsParsingException e) {
      assertEquals("--internal_string=any_value", e.getInvalidArgument());
      assertEquals("Unrecognized option: --internal_string=any_value", e.getMessage());
      assertNotNull(parser.getOptions(ExampleInternalOptions.class));
    }
  }

  @Test
  public void parsingFailsForInternalOptionWithValueInSeparateArgAsIfUnknown() {
    OptionsParser parser = newOptionsParser(ExampleInternalOptions.class);
    List<String> internalOpts = asList("--internal_string", "any_value");
    try {
      parser.parse(internalOpts);
      fail("parsing should have failed for including a private option");
    } catch (OptionsParsingException e) {
      assertEquals("--internal_string", e.getInvalidArgument());
      assertEquals("Unrecognized option: --internal_string", e.getMessage());
      assertNotNull(parser.getOptions(ExampleInternalOptions.class));
    }
  }

  @Test
  public void parseKnownAndUnknownOptions() {
    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    List<String> opts = asList("--bar", "17", "--unknown", "option");
    try {
      parser.parse(opts);
      fail();
    } catch (OptionsParsingException e) {
      assertEquals("--unknown", e.getInvalidArgument());
      assertEquals("Unrecognized option: --unknown", e.getMessage());
      assertNotNull(parser.getOptions(ExampleFoo.class));
      assertNotNull(parser.getOptions(ExampleBaz.class));
    }
  }

  @Test
  public void parseAndOverrideWithEmptyStringToObtainNullValueInOption()
      throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleBoom.class);
    // Override --boom value to the empty string
    parser.parse("--boom=");
    ExampleBoom boom = parser.getOptions(ExampleBoom.class);
    // The converted value is intentionally null since boom uses the EmptyToNullStringConverter
    assertNull(boom.boom);
  }

  public static class CategoryTest extends OptionsBase {
    @Option(
      name = "swiss_bank_account_number",
      optionUsageRestrictions =
          OptionUsageRestrictions.UNDOCUMENTED, // Not printed in usage messages!
      defaultValue = "123456789"
    )
    public int swissBankAccountNumber;

    @Option(name = "student_bank_account_number",
            category = "one",
            defaultValue = "987654321")
    public int studentBankAccountNumber;
  }

  @Test
  public void getOptionsAndGetResidueWithNoCallToParse() {
    // With no call to parse(), all options are at default values, and there's
    // no reside.
    assertEquals("defaultFoo",
                 newOptionsParser(ExampleFoo.class).
                 getOptions(ExampleFoo.class).foo);
    assertEquals(Collections.<String>emptyList(),
                 newOptionsParser(ExampleFoo.class).getResidue());
  }

  @Test
  public void parserCanBeCalledRepeatedly() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleFoo.class);
    parser.parse("--foo", "foo1");
    assertEquals("foo1", parser.getOptions(ExampleFoo.class).foo);
    parser.parse();
    assertEquals("foo1", parser.getOptions(ExampleFoo.class).foo); // no change
    parser.parse("--foo", "foo2");
    assertEquals("foo2", parser.getOptions(ExampleFoo.class).foo); // updated
  }

  @Test
  public void multipleOccuringOption() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleFoo.class);
    parser.parse("--bing", "abcdef", "--foo", "foo1", "--bing", "123456" );
    assertThat(parser.getOptions(ExampleFoo.class).bing).containsExactly("abcdef", "123456");
  }

  @Test
  public void multipleOccurringOptionWithConverter() throws OptionsParsingException {
    // --bang is the same as --bing except that it has a "converter" specified.
    // This test also tests option values with embedded commas and spaces.
    OptionsParser parser = newOptionsParser(ExampleFoo.class);
    parser.parse("--bang", "abc,def ghi", "--foo", "foo1", "--bang", "123456" );
    assertThat(parser.getOptions(ExampleFoo.class).bang).containsExactly("abc,def ghi", "123456");
  }

  @Test
  public void parserIgnoresOptionsAfterMinusMinus()
      throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.parse("--foo", "well", "--baz", "here", "--", "--bar", "ignore");
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    ExampleBaz baz = parser.getOptions(ExampleBaz.class);
    assertEquals("well", foo.foo);
    assertEquals("here", baz.baz);
    assertEquals(42, foo.bar); // the default!
    assertEquals(asList("--bar", "ignore"), parser.getResidue());
  }

  @Test
  public void parserThrowsExceptionIfResidueIsNotAllowed() {
    OptionsParser parser = newOptionsParser(ExampleFoo.class);
    parser.setAllowResidue(false);
    try {
      parser.parse("residue", "is", "not", "OK");
      fail();
    } catch (OptionsParsingException e) {
      assertEquals("Unrecognized arguments: residue is not OK", e.getMessage());
    }
  }

  @Test
  public void multipleCallsToParse() throws Exception {
    OptionsParser parser = newOptionsParser(ExampleFoo.class);
    parser.setAllowResidue(true);
    parser.parse("--foo", "one", "--bar", "43", "unknown1");
    parser.parse("--foo", "two", "unknown2");
    ExampleFoo foo = parser.getOptions(ExampleFoo.class);
    assertEquals("two", foo.foo); // second call takes precedence
    assertEquals(43, foo.bar);
    assertEquals(Arrays.asList("unknown1", "unknown2"), parser.getResidue());
  }

  // Regression test for a subtle bug!  The toString of each options interface
  // instance was printing out key=value pairs for all flags in the
  // OptionsParser, not just those belonging to the specific interface type.
  @Test
  public void toStringDoesntIncludeFlagsForOtherOptionsInParserInstance()
      throws Exception {
    OptionsParser parser = newOptionsParser(ExampleFoo.class, ExampleBaz.class);
    parser.parse("--foo", "foo", "--bar", "43", "--baz", "baz");

    String fooString = parser.getOptions(ExampleFoo.class).toString();
    if (!fooString.contains("foo=foo") ||
        !fooString.contains("bar=43") ||
        !fooString.contains("ExampleFoo") ||
        fooString.contains("baz=baz")) {
      fail("ExampleFoo.toString() is incorrect: " + fooString);
    }

    String bazString = parser.getOptions(ExampleBaz.class).toString();
    if (!bazString.contains("baz=baz") ||
        !bazString.contains("ExampleBaz") ||
        bazString.contains("foo=foo") ||
        bazString.contains("bar=43")) {
      fail("ExampleBaz.toString() is incorrect: " + bazString);
    }
  }

  // Regression test for another subtle bug!  The toString was printing all the
  // explicitly-specified options, even if they were at their default values,
  // causing toString equivalence to diverge from equals().
  @Test
  public void toStringIsIndependentOfExplicitCommandLineOptions() throws Exception {
    ExampleFoo foo1 = Options.parse(ExampleFoo.class).getOptions();
    ExampleFoo foo2 = Options.parse(ExampleFoo.class, "--bar", "42").getOptions();
    assertEquals(foo1, foo2);
    assertEquals(foo1.toString(), foo2.toString());

    Map<String, Object> expectedMap = new ImmutableMap.Builder<String, Object>().
        put("bing", Collections.emptyList()).
        put("bar", 42).
        put("nodoc", "").
        put("bang", Collections.emptyList()).
        put("foo", "defaultFoo").build();

    assertEquals(expectedMap, foo1.asMap());
    assertEquals(expectedMap, foo2.asMap());
  }

  // Regression test for yet another subtle bug!  The inherited options weren't
  // being printed by toString.  One day, a real rain will come and wash all
  // this scummy code off the streets.
  public static class DerivedBaz extends ExampleBaz {
    @Option(name = "derived", defaultValue = "defaultDerived")
    public String derived;
  }

  @Test
  public void toStringPrintsInheritedOptionsToo_Duh() throws Exception {
    DerivedBaz derivedBaz = Options.parse(DerivedBaz.class).getOptions();
    String derivedBazString = derivedBaz.toString();
    if (!derivedBazString.contains("derived=defaultDerived") ||
        !derivedBazString.contains("baz=defaultBaz")) {
      fail("DerivedBaz.toString() is incorrect: " + derivedBazString);
    }
  }

  // Tests for new default value override mechanism
  public static class CustomOptions extends OptionsBase {
    @Option(name = "simple",
        category = "custom",
        defaultValue = "simple default")
    public String simple;

    @Option(name = "multipart_name",
        category = "custom",
        defaultValue = "multipart default")
    public String multipartName;
  }

  @Test
  public void assertDefaultStringsForCustomOptions() throws OptionsParsingException {
    CustomOptions options = Options.parse(CustomOptions.class).getOptions();
    assertEquals("simple default", options.simple);
    assertEquals("multipart default", options.multipartName);
  }

  public static class NullTestOptions extends OptionsBase {
    @Option(name = "simple",
            defaultValue = "null")
    public String simple;
  }

  @Test
  public void defaultNullStringGivesNull() throws Exception {
    NullTestOptions options = Options.parse(NullTestOptions.class).getOptions();
    assertNull(options.simple);
  }

  public static class ImplicitDependencyOptions extends OptionsBase {
    @Option(name = "first",
            implicitRequirements = "--second=second",
            defaultValue = "null")
    public String first;

    @Option(name = "second",
        implicitRequirements = "--third=third",
        defaultValue = "null")
    public String second;

    @Option(name = "third",
        defaultValue = "null")
    public String third;
  }

  @Test
  public void implicitDependencyHasImplicitDependency() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ImplicitDependencyOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--first=first"));
    assertEquals("first", parser.getOptions(ImplicitDependencyOptions.class).first);
    assertEquals("second", parser.getOptions(ImplicitDependencyOptions.class).second);
    assertEquals("third", parser.getOptions(ImplicitDependencyOptions.class).third);
  }

  public static class BadImplicitDependencyOptions extends OptionsBase {
    @Option(name = "first",
            implicitRequirements = "xxx",
            defaultValue = "null")
    public String first;
  }

  @Test
  public void badImplicitDependency() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(BadImplicitDependencyOptions.class);
    try {
      parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--first=first"));
    } catch (AssertionError e) {
      /* Expected error. */
      return;
    }
    fail();
  }

  public static class BadExpansionOptions extends OptionsBase {
    @Option(name = "first",
            expansion = { "xxx" },
            defaultValue = "null")
    public Void first;
  }

  @Test
  public void badExpansionOptions() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(BadExpansionOptions.class);
    try {
      parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--first"));
    } catch (AssertionError e) {
      /* Expected error. */
      return;
    }
    fail();
  }

  /** ConflictingExpansionOptions */
  public static class ConflictingExpansionsOptions extends OptionsBase {

    /** ExpFunc */
    public static class ExpFunc implements ExpansionFunction {
      @Override
      public String[] getExpansion(IsolatedOptionsData optionsData) {
        return new String[] {"--yyy"};
      }
    }

    @Option(
      name = "badness",
      expansion = {"--xxx"},
      expansionFunction = ExpFunc.class,
      defaultValue = "null"
    )
    public Void badness;
  }

  @Test
  public void conflictingExpansions() throws Exception {
    try {
      newOptionsParser(ConflictingExpansionsOptions.class);
      fail("Should have failed due to specifying both expansion and expansionFunction");
    } catch (AssertionError e) {
      assertThat(e.getMessage())
          .contains("Cannot set both expansion and expansionFunction for " + "option --badness");
    }
  }

  /** NullExpansionOptions */
  public static class NullExpansionsOptions extends OptionsBase {

    /** ExpFunc */
    public static class ExpFunc implements ExpansionFunction {
      @Override
      public String[] getExpansion(IsolatedOptionsData optionsData) {
        return null;
      }
    }

    @Option(name = "badness", expansionFunction = ExpFunc.class, defaultValue = "null")
    public Void badness;
  }

  @Test
  public void nullExpansions() throws Exception {
    // Ensure that we get the NPE at the time of parser construction, not later when actually
    // parsing.
    try {
      newOptionsParser(NullExpansionsOptions.class);
      fail("Should have failed due to null expansion function result");
    } catch (OptionsParser.ConstructionException e) {
      assertThat(e.getCause()).isInstanceOf(NullPointerException.class);
      assertThat(e.getCause().getMessage()).contains("null value in entry");
    }
  }

  /** ExpansionOptions */
  public static class ExpansionOptions extends OptionsBase {
    @Option(name = "underlying", defaultValue = "null")
    public String underlying;

    @Option(
      name = "expands",
      expansion = {"--underlying=from_expansion"},
      defaultValue = "null"
    )
    public Void expands;

    /** ExpFunc */
    public static class ExpFunc implements ExpansionFunction {
      @Override
      public String[] getExpansion(IsolatedOptionsData optionsData) {
        return new String[] {"--expands"};
      }
    }

    @Option(name = "expands_by_function", defaultValue = "null", expansionFunction = ExpFunc.class)
    public Void expandsByFunction;
  }

  @Test
  public void describeOptionsWithExpansion() throws Exception {
    // We have to test this here rather than in OptionsTest because expansion functions require
    // that an options parser be constructed.
    OptionsParser parser = OptionsParser.newOptionsParser(ExpansionOptions.class);
    String usage =
        parser.describeOptions(ImmutableMap.<String, String>of(), OptionsParser.HelpVerbosity.LONG);
    assertThat(usage).contains("  --expands\n    Expands to: --underlying=from_expansion");
    assertThat(usage).contains("  --expands_by_function\n    Expands to: --expands");
  }

  @Test
  public void overrideExpansionWithExplicit() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ExpansionOptions.class);
    parser.parse(
        OptionPriority.COMMAND_LINE, null, Arrays.asList("--expands", "--underlying=direct_value"));
    ExpansionOptions options = parser.getOptions(ExpansionOptions.class);
    assertEquals("direct_value", options.underlying);
    assertEquals(0, parser.getWarnings().size());
  }

  @Test
  public void overrideExplicitWithExpansion() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ExpansionOptions.class);
    parser.parse(
        OptionPriority.COMMAND_LINE, null, Arrays.asList("--underlying=direct_value", "--expands"));
    ExpansionOptions options = parser.getOptions(ExpansionOptions.class);
    assertEquals("from_expansion", options.underlying);
  }

  @Test
  public void overrideWithHigherPriority() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(NullTestOptions.class);
    parser.parse(OptionPriority.RC_FILE, null, Arrays.asList("--simple=a"));
    assertEquals("a", parser.getOptions(NullTestOptions.class).simple);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--simple=b"));
    assertEquals("b", parser.getOptions(NullTestOptions.class).simple);
  }

  @Test
  public void overrideWithLowerPriority() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(NullTestOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--simple=a"));
    assertEquals("a", parser.getOptions(NullTestOptions.class).simple);
    parser.parse(OptionPriority.RC_FILE, null, Arrays.asList("--simple=b"));
    assertEquals("a", parser.getOptions(NullTestOptions.class).simple);
  }

  @Test
  public void getOptionValueDescriptionWithNonExistingOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(NullTestOptions.class);
    try {
      parser.getOptionValueDescription("notexisting");
      fail();
    } catch (IllegalArgumentException e) {
      /* Expected exception. */
    }
  }

  @Test
  public void getOptionValueDescriptionWithoutValue() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(NullTestOptions.class);
    assertNull(parser.getOptionValueDescription("simple"));
  }

  @Test
  public void getOptionValueDescriptionWithValue() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(NullTestOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, "my description",
        Arrays.asList("--simple=abc"));
    OptionValueDescription result = parser.getOptionValueDescription("simple");
    assertNotNull(result);
    assertEquals("simple", result.getName());
    assertEquals("abc", result.getValue());
    assertEquals(OptionPriority.COMMAND_LINE, result.getPriority());
    assertEquals("my description", result.getSource());
    assertNull(result.getImplicitDependant());
    assertFalse(result.isImplicitDependency());
    assertNull(result.getExpansionParent());
    assertFalse(result.isExpansion());
  }

  public static class ImplicitDependencyWarningOptions extends OptionsBase {
    @Option(name = "first",
            implicitRequirements = "--second=second",
            defaultValue = "null")
    public String first;

    @Option(name = "second",
        defaultValue = "null")
    public String second;

    @Option(name = "third",
            implicitRequirements = "--second=third",
            defaultValue = "null")
    public String third;
  }

  @Test
  public void warningForImplicitOverridingExplicitOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ImplicitDependencyWarningOptions.class);
    parser.parse("--second=second", "--first=first");
    assertThat(parser.getWarnings())
        .containsExactly("Option 'second' is implicitly defined by "
                         + "option 'first'; the implicitly set value overrides the previous one");
  }

  @Test
  public void warningForExplicitOverridingImplicitOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ImplicitDependencyWarningOptions.class);
    parser.parse("--first=first");
    assertThat(parser.getWarnings()).isEmpty();
    parser.parse("--second=second");
    assertThat(parser.getWarnings())
        .containsExactly("A new value for option 'second' overrides a"
                         + " previous implicit setting of that option by option 'first'");
  }

  @Test
  public void warningForExplicitOverridingImplicitOptionInSameCall() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ImplicitDependencyWarningOptions.class);
    parser.parse("--first=first", "--second=second");
    assertThat(parser.getWarnings())
        .containsExactly("Option 'second' is implicitly defined by "
                         + "option 'first'; the implicitly set value overrides the previous one");
  }

  @Test
  public void warningForImplicitOverridingImplicitOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ImplicitDependencyWarningOptions.class);
    parser.parse("--first=first");
    assertThat(parser.getWarnings()).isEmpty();
    parser.parse("--third=third");
    assertThat(parser.getWarnings())
        .containsExactly("Option 'second' is implicitly defined by both "
                         + "option 'first' and option 'third'");
  }

  public static class WarningOptions extends OptionsBase {
    @Deprecated
    @Option(name = "first",
            defaultValue = "null")
    public Void first;

    @Deprecated
    @Option(name = "second",
            allowMultiple = true,
            defaultValue = "null")
    public List<String> second;

    @Deprecated
    @Option(name = "third",
            expansion = "--fourth=true",
            abbrev = 't',
            defaultValue = "null")
    public Void third;

    @Option(name = "fourth",
            defaultValue = "false")
    public boolean fourth;
  }

  @Test
  public void deprecationWarning() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(WarningOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--first"));
    assertEquals(Arrays.asList("Option 'first' is deprecated"), parser.getWarnings());
  }

  @Test
  public void deprecationWarningForListOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(WarningOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--second=a"));
    assertEquals(Arrays.asList("Option 'second' is deprecated"), parser.getWarnings());
  }

  @Test
  public void deprecationWarningForExpansionOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(WarningOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--third"));
    assertEquals(Arrays.asList("Option 'third' is deprecated"), parser.getWarnings());
    assertTrue(parser.getOptions(WarningOptions.class).fourth);
  }

  @Test
  public void deprecationWarningForAbbreviatedExpansionOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(WarningOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("-t"));
    assertEquals(Arrays.asList("Option 'third' is deprecated"), parser.getWarnings());
    assertTrue(parser.getOptions(WarningOptions.class).fourth);
  }

  public static class NewWarningOptions extends OptionsBase {
    @Option(name = "first",
            defaultValue = "null",
            deprecationWarning = "it's gone")
    public Void first;

    @Option(name = "second",
            allowMultiple = true,
            defaultValue = "null",
            deprecationWarning = "sorry, no replacement")
    public List<String> second;

    @Option(name = "third",
            expansion = "--fourth=true",
            defaultValue = "null",
            deprecationWarning = "use --forth instead")
    public Void third;

    @Option(name = "fourth",
            defaultValue = "false")
    public boolean fourth;
  }

  @Test
  public void newDeprecationWarning() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(NewWarningOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--first"));
    assertEquals(Arrays.asList("Option 'first' is deprecated: it's gone"), parser.getWarnings());
  }

  @Test
  public void newDeprecationWarningForListOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(NewWarningOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--second=a"));
    assertEquals(Arrays.asList("Option 'second' is deprecated: sorry, no replacement"),
        parser.getWarnings());
  }

  @Test
  public void newDeprecationWarningForExpansionOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(NewWarningOptions.class);
    parser.parse(OptionPriority.COMMAND_LINE, null, Arrays.asList("--third"));
    assertEquals(Arrays.asList("Option 'third' is deprecated: use --forth instead"),
        parser.getWarnings());
    assertTrue(parser.getOptions(NewWarningOptions.class).fourth);
  }

  public static class ExpansionWarningOptions extends OptionsBase {
    @Option(
      name = "first",
      expansion = "--underlying=other",
      defaultValue = "null"
    )
    public Void first;

    @Option(
      name = "second",
      expansion = "--underlying=other",
      defaultValue = "null"
    )
    public Void second;

    @Option(
      name = "underlying",
      defaultValue = "null"
    )
    public String underlying;
  }

  @Test
  public void warningForExpansionOverridingExplicitOption() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ExpansionWarningOptions.class);
    parser.parse("--underlying=underlying", "--first");
    assertThat(parser.getWarnings()).containsExactly(
        "The option 'first' was expanded and now overrides a "
        + "previous explicitly specified option 'underlying'");
  }

  @Test
  public void warningForTwoConflictingExpansionOptions() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ExpansionWarningOptions.class);
    parser.parse("--first", "--second");
    assertThat(parser.getWarnings()).containsExactly(
        "The option 'underlying' was expanded to from both options 'first' " + "and 'second'");
  }

  // This test is here to make sure that nobody accidentally changes the
  // order of the enum values and breaks the implicit assumptions elsewhere
  // in the code.
  @Test
  public void optionPrioritiesAreCorrectlyOrdered() throws Exception {
    assertEquals(6, OptionPriority.values().length);
    assertThat(OptionPriority.DEFAULT).isLessThan(OptionPriority.COMPUTED_DEFAULT);
    assertThat(OptionPriority.COMPUTED_DEFAULT).isLessThan(OptionPriority.RC_FILE);
    assertThat(OptionPriority.RC_FILE).isLessThan(OptionPriority.COMMAND_LINE);
    assertThat(OptionPriority.COMMAND_LINE).isLessThan(OptionPriority.INVOCATION_POLICY);
    assertThat(OptionPriority.INVOCATION_POLICY).isLessThan(OptionPriority.SOFTWARE_REQUIREMENT);
  }

  public static class IntrospectionExample extends OptionsBase {
    @Option(name = "alpha",
            category = "one",
            defaultValue = "alpha")
    public String alpha;

    @Option(name = "beta",
            category = "one",
            defaultValue = "beta")
    public String beta;

    @Option(
      name = "gamma",
      optionUsageRestrictions = OptionUsageRestrictions.UNDOCUMENTED,
      defaultValue = "gamma"
    )
    public String gamma;

    @Option(
      name = "delta",
      optionUsageRestrictions = OptionUsageRestrictions.UNDOCUMENTED,
      defaultValue = "delta"
    )
    public String delta;

    @Option(
      name = "echo",
      optionUsageRestrictions = OptionUsageRestrictions.HIDDEN,
      defaultValue = "echo"
    )
    public String echo;
  }

  @Test
  public void asListOfUnparsedOptions() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(IntrospectionExample.class);
    parser.parse(OptionPriority.COMMAND_LINE, "source",
        Arrays.asList("--alpha=one", "--gamma=two", "--echo=three"));
    List<UnparsedOptionValueDescription> result = parser.asListOfUnparsedOptions();
    assertNotNull(result);
    assertEquals(3, result.size());

    assertEquals("alpha", result.get(0).getName());
    assertEquals(true, result.get(0).isDocumented());
    assertEquals(false, result.get(0).isHidden());
    assertEquals("one", result.get(0).getUnparsedValue());
    assertEquals("source", result.get(0).getSource());
    assertEquals(OptionPriority.COMMAND_LINE, result.get(0).getPriority());

    assertEquals("gamma", result.get(1).getName());
    assertEquals(false, result.get(1).isDocumented());
    assertEquals(false, result.get(1).isHidden());
    assertEquals("two", result.get(1).getUnparsedValue());
    assertEquals("source", result.get(1).getSource());
    assertEquals(OptionPriority.COMMAND_LINE, result.get(1).getPriority());

    assertEquals("echo", result.get(2).getName());
    assertEquals(false, result.get(2).isDocumented());
    assertEquals(true, result.get(2).isHidden());
    assertEquals("three", result.get(2).getUnparsedValue());
    assertEquals("source", result.get(2).getSource());
    assertEquals(OptionPriority.COMMAND_LINE, result.get(2).getPriority());
  }

  @Test
  public void asListOfExplicitOptions() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(IntrospectionExample.class);
    parser.parse(OptionPriority.COMMAND_LINE, "source",
        Arrays.asList("--alpha=one", "--gamma=two"));
    List<UnparsedOptionValueDescription> result = parser.asListOfExplicitOptions();
    assertNotNull(result);
    assertEquals(2, result.size());

    assertEquals("alpha", result.get(0).getName());
    assertEquals(true, result.get(0).isDocumented());
    assertEquals("one", result.get(0).getUnparsedValue());
    assertEquals("source", result.get(0).getSource());
    assertEquals(OptionPriority.COMMAND_LINE, result.get(0).getPriority());

    assertEquals("gamma", result.get(1).getName());
    assertEquals(false, result.get(1).isDocumented());
    assertEquals("two", result.get(1).getUnparsedValue());
    assertEquals("source", result.get(1).getSource());
    assertEquals(OptionPriority.COMMAND_LINE, result.get(1).getPriority());
  }

  private void assertOptionValue(String expectedName, Object expectedValue,
      OptionPriority expectedPriority, String expectedSource,
      OptionValueDescription actual) {
    assertNotNull(actual);
    assertEquals(expectedName, actual.getName());
    assertEquals(expectedValue, actual.getValue());
    assertEquals(expectedPriority, actual.getPriority());
    assertEquals(expectedSource, actual.getSource());
  }

  @Test
  public void asListOfEffectiveOptions() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(IntrospectionExample.class);
    parser.parse(OptionPriority.COMMAND_LINE, "source",
        Arrays.asList("--alpha=one", "--gamma=two"));
    List<OptionValueDescription> result = parser.asListOfEffectiveOptions();
    assertNotNull(result);
    assertEquals(5, result.size());
    HashMap<String,OptionValueDescription> map = new HashMap<String,OptionValueDescription>();
    for (OptionValueDescription description : result) {
      map.put(description.getName(), description);
    }

    assertOptionValue("alpha", "one", OptionPriority.COMMAND_LINE, "source",
        map.get("alpha"));
    assertOptionValue("beta", "beta", OptionPriority.DEFAULT, null,
        map.get("beta"));
    assertOptionValue("gamma", "two", OptionPriority.COMMAND_LINE, "source",
        map.get("gamma"));
    assertOptionValue("delta", "delta", OptionPriority.DEFAULT, null,
        map.get("delta"));
    assertOptionValue("echo", "echo", OptionPriority.DEFAULT, null,
        map.get("echo"));
  }

  // Regression tests for bug:
  // "--option from blazerc unexpectedly overrides --option from command line"
  public static class ListExample extends OptionsBase {
    @Option(name = "alpha",
            converter = StringConverter.class,
            allowMultiple = true,
            defaultValue = "null")
    public List<String> alpha;
  }

  @Test
  public void overrideListOptions() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(ListExample.class);
    parser.parse(OptionPriority.COMMAND_LINE, "a", Arrays.asList("--alpha=two"));
    parser.parse(OptionPriority.RC_FILE, "b", Arrays.asList("--alpha=one"));
    assertEquals(Arrays.asList("one", "two"), parser.getOptions(ListExample.class).alpha);
  }

  public static class CommaSeparatedOptionsExample extends OptionsBase {
    @Option(name = "alpha",
            converter = CommaSeparatedOptionListConverter.class,
            allowMultiple = true,
            defaultValue = "null")
    public List<String> alpha;
  }

  @Test
  public void commaSeparatedOptionsWithAllowMultiple() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(CommaSeparatedOptionsExample.class);
    parser.parse(OptionPriority.COMMAND_LINE, "a", Arrays.asList("--alpha=one",
        "--alpha=two,three"));
    assertEquals(Arrays.asList("one", "two", "three"),
        parser.getOptions(CommaSeparatedOptionsExample.class).alpha);
  }

  public static class IllegalListTypeExample extends OptionsBase {
    @Option(name = "alpha",
            converter = CommaSeparatedOptionListConverter.class,
            allowMultiple = true,
            defaultValue = "null")
    public List<Integer> alpha;
  }

  @Test
  public void illegalListType() throws Exception {
    try {
      OptionsParser.newOptionsParser(IllegalListTypeExample.class);
    } catch (ConstructionException e) {
      // Expected exception
      return;
    }
    fail();
  }

  public static class Yesterday extends OptionsBase {

    @Option(name = "a",
            defaultValue = "a")
    public String a;

    @Option(name = "b",
            defaultValue = "b")
    public String b;

    @Option(name = "c",
            defaultValue = "null",
            expansion = {"--a=0"})
    public Void c;

    @Option(name = "d",
            defaultValue = "null",
            allowMultiple = true)
    public List<String> d;

    @Option(name = "e",
            defaultValue = "null",
            implicitRequirements = { "--a==1" })
    public String e;

    @Option(name = "f",
            defaultValue = "null",
            implicitRequirements = { "--b==1" })
    public String f;

    @Option(name = "g",
            abbrev = 'h',
            defaultValue = "false")
    public boolean g;
  }

  public static List<String> canonicalize(Class<? extends OptionsBase> optionsClass, String... args)
      throws OptionsParsingException {

    OptionsParser parser = OptionsParser.newOptionsParser(
        ImmutableList.<Class<? extends OptionsBase>>of(optionsClass));
    parser.setAllowResidue(false);
    parser.parse(Arrays.asList(args));
    return parser.canonicalize();
  }

  @Test
  public void canonicalizeEasy() throws Exception {
    assertEquals(Arrays.asList("--a=x"), canonicalize(Yesterday.class, "--a=x"));
  }

  @Test
  public void canonicalizeSkipDuplicate() throws Exception {
    assertEquals(Arrays.asList("--a=x"), canonicalize(Yesterday.class, "--a=y", "--a=x"));
  }

  @Test
  public void canonicalizeExpands() throws Exception {
    assertEquals(Arrays.asList("--a=0"), canonicalize(Yesterday.class, "--c"));
  }

  @Test
  public void canonicalizeExpansionOverridesExplicit() throws Exception {
    assertEquals(Arrays.asList("--a=0"), canonicalize(Yesterday.class, "--a=x", "--c"));
  }

  @Test
  public void canonicalizeExplicitOverridesExpansion() throws Exception {
    assertEquals(Arrays.asList("--a=x"), canonicalize(Yesterday.class, "--c", "--a=x"));
  }

  @Test
  public void canonicalizeSorts() throws Exception {
    assertEquals(Arrays.asList("--a=x", "--b=y"), canonicalize(Yesterday.class, "--b=y", "--a=x"));
  }

  @Test
  public void canonicalizeImplicitDepsAtEnd() throws Exception {
    assertEquals(Arrays.asList("--a=x", "--e=y"), canonicalize(Yesterday.class, "--e=y", "--a=x"));
  }

  @Test
  public void canonicalizeImplicitDepsSkipsDuplicate() throws Exception {
    assertEquals(Arrays.asList("--e=y"), canonicalize(Yesterday.class, "--e=x", "--e=y"));
  }

  @Test
  public void canonicalizeDoesNotSortImplicitDeps() throws Exception {
    assertEquals(Arrays.asList("--a=x", "--f=z", "--e=y"),
        canonicalize(Yesterday.class, "--f=z", "--e=y", "--a=x"));
  }

  @Test
  public void canonicalizeDoesNotSkipAllowMultiple() throws Exception {
    assertEquals(Arrays.asList("--d=a", "--d=b"),
        canonicalize(Yesterday.class, "--d=a", "--d=b"));
  }

  @Test
  public void canonicalizeReplacesAbbrevWithName() throws Exception {
    assertEquals(Arrays.asList("--g=1"),
        canonicalize(Yesterday.class, "-h"));
  }

  public static class LongValueExample extends OptionsBase {
    @Option(name = "longval",
            defaultValue = "2147483648")
    public long longval;

    @Option(name = "intval",
            defaultValue = "2147483647")
    public int intval;
  }

  @Test
  public void parseLong() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(LongValueExample.class);
    parser.parse("");
    LongValueExample result = parser.getOptions(LongValueExample.class);
    assertEquals(2147483648L, result.longval);
    assertEquals(2147483647, result.intval);

    parser.parse("--longval", Long.toString(Long.MIN_VALUE));
    result = parser.getOptions(LongValueExample.class);
    assertEquals(Long.MIN_VALUE, result.longval);

    try {
      parser.parse("--intval=2147483648");
      fail();
    } catch (OptionsParsingException e) {
    }

    parser.parse("--longval", "100");
    result = parser.getOptions(LongValueExample.class);
    assertEquals(100, result.longval);
  }

  public static class OldNameExample extends OptionsBase {
    @Option(name = "new_name",
            oldName = "old_name",
            defaultValue = "defaultValue")
    public String flag;
  }

  @Test
  public void testOldName() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(OldNameExample.class);
    parser.parse("--old_name=foo");
    OldNameExample result = parser.getOptions(OldNameExample.class);
    assertEquals("foo", result.flag);

    // Should also work by its new name.
    parser = newOptionsParser(OldNameExample.class);
    parser.parse("--new_name=foo");
    result = parser.getOptions(OldNameExample.class);
    assertEquals("foo", result.flag);
    // Should be no warnings if the new name is used.
    assertThat(parser.getWarnings()).isEmpty();
  }

  @Test
  public void testOldNameCanonicalization() throws Exception {
    assertEquals(
        Arrays.asList("--new_name=foo"), canonicalize(OldNameExample.class, "--old_name=foo"));
  }

  public static class ExampleBooleanFooOptions extends OptionsBase {
    @Option(name = "foo", defaultValue = "false")
    public boolean foo;
  }

  @Test
  public void testBooleanUnderscorePrefixError() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(ExampleBooleanFooOptions.class);
    parser.parse("--no_foo");
    ExampleBooleanFooOptions result = parser.getOptions(ExampleBooleanFooOptions.class);
    assertThat(result.foo).isFalse();
    List<String> warning = parser.getWarnings();
    assertThat(warning).hasSize(1);
    assertThat(warning.get(0)).contains("Option 'foo' is specified using the deprecated "
          + "--no_ prefix. Use --no without the underscore instead");

  }

  public static class WrapperOptionExample extends OptionsBase {
    @Option(name = "wrapper",
            defaultValue = "null",
            wrapperOption = true)
    public Void wrapperOption;

    @Option(name = "flag1", defaultValue = "false")
    public boolean flag1;

    @Option(name = "flag2", defaultValue = "42")
    public int flag2;

    @Option(name = "flag3", defaultValue = "foo")
    public String flag3;
  }

  @Test
  public void testWrapperOption() throws OptionsParsingException {
    OptionsParser parser = newOptionsParser(WrapperOptionExample.class);
    parser.parse("--wrapper=--flag1=true", "--wrapper=--flag2=87", "--wrapper=--flag3=bar");
    WrapperOptionExample result = parser.getOptions(WrapperOptionExample.class);
    assertEquals(true, result.flag1);
    assertEquals(87, result.flag2);
    assertEquals("bar", result.flag3);
  }

  @Test
  public void testInvalidWrapperOptionFormat() {
    OptionsParser parser = newOptionsParser(WrapperOptionExample.class);
    try {
      parser.parse("--wrapper=foo");
      fail();
    } catch (OptionsParsingException e) {
      // Check that the message looks like it's suggesting the correct format.
      assertThat(e.getMessage()).contains("--foo");
    }
  }

  @Test
  public void testWrapperCanonicalization() throws OptionsParsingException {
    List<String> canonicalized = canonicalize(WrapperOptionExample.class,
        "--wrapper=--flag1=true", "--wrapper=--flag2=87", "--wrapper=--flag3=bar");
    assertEquals(Arrays.asList("--flag1=true", "--flag2=87", "--flag3=bar"), canonicalized);
  }

  /** Dummy options that declares it uses only core types. */
  @UsesOnlyCoreTypes
  public static class CoreTypesOptions extends OptionsBase implements Serializable {
    @Option(name = "foo", defaultValue = "false")
    public boolean foo;

    @Option(name = "bar", defaultValue = "abc")
    public String bar;
  }

  /** Dummy options that does not declare using only core types. */
  public static class NonCoreTypesOptions extends OptionsBase {
    @Option(name = "foo", defaultValue = "false")
    public boolean foo;
  }

  /** Dummy options that incorrectly claims to use only core types. */
  @UsesOnlyCoreTypes
  public static class BadCoreTypesOptions extends OptionsBase {
    /** Dummy unsafe type. */
    public static class Foo {
      public int i = 0;
    }

    /** Converter for Foo. */
    public static class FooConverter implements Converter<Foo> {
      @Override
      public Foo convert(String input) throws OptionsParsingException {
        Foo foo = new Foo();
        foo.i = Integer.parseInt(input);
        return foo;
      }

      @Override
      public String getTypeDescription() {
        return "a foo";
      }
    }

    @Option(name = "foo", defaultValue = "null", converter = FooConverter.class)
    public Foo foo;
  }

  /** Dummy options that is unsafe for @UsesOnlyCoreTypes but doesn't use the annotation. */
  public static class SuperBadCoreTypesOptions extends OptionsBase {
    @Option(name = "foo", defaultValue = "null", converter = BadCoreTypesOptions.FooConverter.class)
    public BadCoreTypesOptions.Foo foo;
  }

  /**
   * Dummy options that illegally advertises @UsesOnlyCoreTypes, when its direct fields are fine but
   * its inherited fields are not.
   */
  @UsesOnlyCoreTypes
  public static class InheritedBadCoreTypesOptions extends SuperBadCoreTypesOptions {
    @Option(name = "bar", defaultValue = "false")
    public boolean bar;
  }

  @Test
  public void testUsesOnlyCoreTypes() {
    assertThat(OptionsParser.getUsesOnlyCoreTypes(CoreTypesOptions.class)).isTrue();
    assertThat(OptionsParser.getUsesOnlyCoreTypes(NonCoreTypesOptions.class)).isFalse();
  }

  @Test
  public void testValidationOfUsesOnlyCoreTypes() {
    try {
      OptionsParser.getUsesOnlyCoreTypes(BadCoreTypesOptions.class);
      fail("Should have detected illegal use of @UsesOnlyCoreTypes");
    } catch (OptionsParser.ConstructionException expected) {
      assertThat(expected.getMessage()).matches(
          "Options class '.*BadCoreTypesOptions' is marked as @UsesOnlyCoreTypes, but field "
          + "'foo' has type '.*Foo'");
    }
  }

  @Test
  public void testValidationOfUsesOnlyCoreTypes_Inherited() {
    try {
      OptionsParser.getUsesOnlyCoreTypes(InheritedBadCoreTypesOptions.class);
      fail("Should have detected illegal use of @UsesOnlyCoreTypes "
          + "(due to inheritance from bad superclass)");
    } catch (OptionsParser.ConstructionException expected) {
      assertThat(expected.getMessage()).matches(
          "Options class '.*InheritedBadCoreTypesOptions' is marked as @UsesOnlyCoreTypes, but "
          + "field 'foo' has type '.*Foo'");
    }
  }

  @Test
  public void serializable() throws Exception {
    OptionsParser parser = OptionsParser.newOptionsParser(CoreTypesOptions.class);
    parser.parse("--foo=true", "--bar=xyz");
    CoreTypesOptions options = parser.getOptions(CoreTypesOptions.class);

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ObjectOutputStream objOut = new ObjectOutputStream(bos);
    objOut.writeObject(options);
    objOut.flush();
    ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
    ObjectInputStream objIn = new ObjectInputStream(bis);
    Object obj = objIn.readObject();

    assertThat(obj).isEqualTo(options);
  }

  @Test
  public void stableSerialization() throws Exception {
    // Construct options two different ways to get the same result, and confirm that the serialized
    // representation is identical.
    OptionsParser parser1 = OptionsParser.newOptionsParser(CoreTypesOptions.class);
    parser1.parse("--foo=true", "--bar=xyz");
    CoreTypesOptions options1 = parser1.getOptions(CoreTypesOptions.class);
    OptionsParser parser2 = OptionsParser.newOptionsParser(CoreTypesOptions.class);
    parser2.parse("--bar=abc", "--foo=1");
    CoreTypesOptions options2 = parser2.getOptions(CoreTypesOptions.class);
    options2.bar = "xyz";

    // We use two different pairs of streams because ObjectOutputStream#reset does not actually
    // wipe all the internal state. (The first time it's used, there's an additional header that
    // does not reappear afterwards.)
    ByteArrayOutputStream bos1 = new ByteArrayOutputStream();
    ObjectOutputStream objOut1 = new ObjectOutputStream(bos1);
    objOut1.writeObject(options1);
    objOut1.flush();
    byte[] data1 = bos1.toByteArray();
    ByteArrayOutputStream bos2 = new ByteArrayOutputStream();
    ObjectOutputStream objOut2 = new ObjectOutputStream(bos2);
    objOut2.writeObject(options2);
    objOut2.flush();
    byte[] data2 = bos2.toByteArray();

    assertThat(data1).isEqualTo(data2);
  }
}

