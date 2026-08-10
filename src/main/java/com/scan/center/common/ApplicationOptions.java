package com.scan.center.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ApplicationOptions {
  public static final List<String> VALUES = Collections.unmodifiableList(Arrays.asList(
      "F-BASE", "F-GMO", "F-GMRM", "F-FMPV", "F-EFM", "F-SCIS", "ALL"));

  public static boolean contains(String value) {
    return value != null && VALUES.contains(value.trim());
  }

  private ApplicationOptions() {}
}
