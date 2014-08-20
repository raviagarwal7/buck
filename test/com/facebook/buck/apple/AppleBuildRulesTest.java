/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppleBuildRulesTest {

  @Test
  public void testAppleLibraryIsXcodeTargetBuildRuleType() throws Exception {
    assertTrue(AppleBuildRules.isXcodeTargetBuildRuleType(AppleLibraryDescription.TYPE));
  }

  @Test
  public void testIosResourceIsNotXcodeTargetBuildRuleType() throws Exception {
    assertFalse(AppleBuildRules.isXcodeTargetBuildRuleType(AppleResourceDescription.TYPE));
  }

  @Test
  public void testIosTestIsXcodeTargetTestBuildRuleType() throws Exception {
    assertTrue(AppleBuildRules.isXcodeTargetTestBuildRuleType(IosTestDescription.TYPE));
  }

  @Test
  public void testAppleLibraryIsNotXcodeTargetTestBuildRuleType() throws Exception {
    assertFalse(AppleBuildRules.isXcodeTargetTestBuildRuleType(AppleLibraryDescription.TYPE));
  }
}
