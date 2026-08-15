package com.signalforge.iam.domain;

import java.util.Locale;

/**
 * The three roles SignalForge recognises, ordered by privilege.
 *
 * <p>{@code rank} mirrors the {@code roles} table so authorization checks can express "at least
 * ENGINEER" without hardcoding a list of role names at every call site.
 */
public enum Role {
  VIEWER(10),
  ENGINEER(20),
  ADMIN(30);

  /** Spring Security convention: authorities carry a ROLE_ prefix. */
  public static final String PREFIX = "ROLE_";

  private final int rank;

  Role(int rank) {
    this.rank = rank;
  }

  public int rank() {
    return rank;
  }

  public String authority() {
    return PREFIX + name();
  }

  /** True when this role is at least as privileged as {@code required}. */
  public boolean satisfies(Role required) {
    return this.rank >= required.rank;
  }

  public static Role from(String code) {
    if (code == null) {
      throw new IllegalArgumentException("role code is required");
    }
    return Role.valueOf(code.trim().toUpperCase(Locale.ROOT));
  }
}
