package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public enum Permission {
  C,
  R,
  U,
  D;
  public static final ImmutableSet<Permission> CRU = Sets.immutableEnumSet(C, R, U);
  public static final ImmutableSet<Permission> CRUD = Sets.immutableEnumSet(C, R, U, D);

  public static String toCCDPerm(Set<Permission> perms) {
    return perms.stream()
        .sorted(Ordering.explicit(C, R, U, D))
        .map(Enum::toString).collect(Collectors.joining(""));
  }

  public static EnumSet<Permission> fromCCDPerm(String perms) {
    EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
    for (char c : perms.toCharArray()) {
      result.add(Enum.valueOf(Permission.class, String.valueOf(c)));
    }

    return result;
  }

}
