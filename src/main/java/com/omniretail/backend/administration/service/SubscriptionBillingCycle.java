package com.omniretail.backend.administration.service;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
/** UTC calendar anchor, matching the frontend billing snapshot convention. */
public record SubscriptionBillingCycle(Instant start, Instant end) {
 public static SubscriptionBillingCycle at(Instant startedAt, Instant now) {
  LocalDate anchor = startedAt.atZone(ZoneOffset.UTC).toLocalDate();
  long months = Math.max(0, ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(now.atZone(ZoneOffset.UTC))));
  if (monthly(anchor, months).isAfter(now)) months = Math.max(0, months - 1);
  return new SubscriptionBillingCycle(monthly(anchor, months), monthly(anchor, months + 1));
 }
 private static Instant monthly(LocalDate anchor, long offset) {
  YearMonth month = YearMonth.from(anchor).plusMonths(offset);
  return month.atDay(Math.min(anchor.getDayOfMonth(), month.lengthOfMonth())).atStartOfDay(ZoneOffset.UTC).toInstant();
 }
}
