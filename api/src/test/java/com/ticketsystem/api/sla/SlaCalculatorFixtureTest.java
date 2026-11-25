package com.ticketsystem.api.sla;

import static com.ticketsystem.api.sla.SlaFixtures.at;
import static com.ticketsystem.api.sla.SlaFixtures.calendar;
import static com.ticketsystem.api.sla.SlaFixtures.history;
import static com.ticketsystem.api.sla.SlaFixtures.local;
import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Hand-calculated reference cases for the SLA engine, kept as the reference for the WP-09 report
 * maths (WP-09a test plan). Every time is a Europe/London local time; "-" means none.
 *
 * <p>Calendars (see {@link SlaFixtures}): STANDARD is Monday-Friday 08:30-17:30 (540 min = one
 * business day) with holidays Fri 25 Dec 2026, Mon 28 Dec 2026 and Fri 1 Jan 2027; NO_WEDNESDAY
 * drops Wednesday; SHORT_FRIDAY closes 12:30 on Friday. Default policy (spec 4.7): P1 30 min / 4 h
 * 24/7; P2 60 / 480 business min; P3 240 / 1,620; P4 540 / 5,400. Calendar dates: Sat 21 Nov 2026,
 * Mon 23 Nov 2026; London clocks go back Sun 25 Oct 2026 02:00 and forward Sun 28 Mar 2027 01:00.
 *
 * <p>Columns: case | calendar | vendor pauses | priority | history (status@time, first = creation)
 * | first response | as of | FR due | FR state | resolution due | resolution state | resolution
 * used min | paused min.
 */
class SlaCalculatorFixtureTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # Spec 12: P1 created Saturday 23:00 is due for first response 23:30 the same night; 4 h resolution.
          F01 spec 12 P1 Sat 23:00                 | STANDARD     | false | P1 | NEW@2026-11-21T23:00 | - | 2026-11-21T23:00 | 2026-11-21T23:30 | RUNNING  | 2026-11-22T03:00 | RUNNING  | 0   | 0
          # Spec 12: P3 created then: 4 business hours from Mon 08:30 = 12:30; 3 days = Mon+Tue+Wed full.
          F02 spec 12 P3 Sat 23:00                 | STANDARD     | false | P3 | NEW@2026-11-21T23:00 | - | 2026-11-21T23:00 | 2026-11-23T12:30 | RUNNING  | 2026-11-25T17:30 | RUNNING  | 0   | 0
          # 17:59 Friday is after the 17:30 close: everything starts Mon 08:30 (60 -> 09:30, 480 -> 16:30).
          F03 P2 Fri 17:59 after close             | STANDARD     | false | P2 | NEW@2026-11-20T17:59 | - | 2026-11-20T17:59 | 2026-11-23T09:30 | RUNNING  | 2026-11-23T16:30 | RUNNING  | 0   | 0
          # 17:29 Friday: 1 min Friday + 59 Monday = 09:29; 1 + 479 = Mon 16:29.
          F04 P2 Fri 17:29 last minute             | STANDARD     | false | P2 | NEW@2026-11-20T17:29 | - | 2026-11-20T17:29 | 2026-11-23T09:29 | RUNNING  | 2026-11-23T16:29 | RUNNING  | 0   | 0
          # 1 business day = 450 Wed + 90 Thu; 10 business days = same time two weeks later.
          F05 P4 Wed 10:00 days targets            | STANDARD     | false | P4 | NEW@2026-11-18T10:00 | - | 2026-11-18T10:00 | 2026-11-19T10:00 | RUNNING  | 2026-12-02T10:00 | RUNNING  | 0   | 0
          # Pending on client Mon 10:00 -> Tue 10:00 pauses 450 + 90 = 540; used 60 + 60; due Thu 09:00 + 1 day = Fri 09:00.
          F06 P3 pending on client pauses          | STANDARD     | false | P3 | NEW@2026-11-23T09:00, PENDING_ON_CLIENT@2026-11-23T10:00, OPEN@2026-11-24T10:00 | 2026-11-23T10:00 | 2026-11-24T11:00 | 2026-11-23T13:00 | MET      | 2026-11-27T09:00 | RUNNING  | 120 | 540
          # Pending on vendor keeps running by default: used Mon 510 + Tue 150 = 660, due unchanged Thu 09:00.
          F07 P3 pending on vendor runs (default)  | STANDARD     | false | P3 | NEW@2026-11-23T09:00, PENDING_ON_VENDOR@2026-11-23T10:00, OPEN@2026-11-24T10:00 | 2026-11-23T10:00 | 2026-11-24T11:00 | 2026-11-23T13:00 | MET      | 2026-11-26T09:00 | RUNNING  | 660 | 0
          # The policy flag makes Pending on vendor pause exactly like Pending on client (F06).
          F08 P3 pending on vendor pauses (flag)   | STANDARD     | true  | P3 | NEW@2026-11-23T09:00, PENDING_ON_VENDOR@2026-11-23T10:00, OPEN@2026-11-24T10:00 | 2026-11-23T10:00 | 2026-11-24T11:00 | 2026-11-23T13:00 | MET      | 2026-11-27T09:00 | RUNNING  | 120 | 540
          # Resolved after 180, reopened Tue 09:00: clock continues from 180; resolved time is not paused time.
          F09 P2 reopen continues the clock        | STANDARD     | false | P2 | OPEN@2026-11-23T09:00, RESOLVED@2026-11-23T12:00, OPEN@2026-11-24T09:00 | 2026-11-23T09:30 | 2026-11-24T10:00 | 2026-11-23T10:00 | MET      | 2026-11-24T14:00 | RUNNING  | 240 | 0
          # The final Resolved counts: 180 + 240 = 420 <= 480.
          F10 P2 final resolved counts             | STANDARD     | false | P2 | OPEN@2026-11-23T09:00, RESOLVED@2026-11-23T12:00, OPEN@2026-11-24T09:00, RESOLVED@2026-11-24T13:00 | 2026-11-23T09:30 | 2026-11-24T13:00 | 2026-11-23T10:00 | MET      | -                | MET      | 420 | 0
          # No reply, still open a day later: used 510 + 30 = 540 > 60 and > 480.
          F11 P2 both targets breached             | STANDARD     | false | P2 | NEW@2026-11-23T09:00 | - | 2026-11-24T09:00 | 2026-11-23T10:00 | BREACHED | 2026-11-23T17:00 | BREACHED | 540 | 0
          # Thu 24 Dec 16:00: 90 Thu, skip Fri 25 holiday + weekend + Mon 28 holiday, Tue 29 +150 = 11:00; 90+540+540+450.
          F12 P3 over Christmas holidays           | STANDARD     | false | P3 | NEW@2026-12-24T16:00 | - | 2026-12-24T16:00 | 2026-12-29T11:00 | RUNNING  | 2026-12-31T16:00 | RUNNING  | 0   | 0
          # Thu 31 Dec 12:00: 330 Thu, skip Fri 1 Jan holiday, Mon 4 Jan +210 = 12:00; 10 days ends Fri 15 Jan 12:00.
          F13 P4 across the year boundary          | STANDARD     | false | P4 | NEW@2026-12-31T12:00 | - | 2026-12-31T12:00 | 2027-01-04T12:00 | RUNNING  | 2027-01-15T12:00 | RUNNING  | 0   | 0
          # P1 24/7 ignores the New Year holiday.
          F14 P1 24/7 across the year boundary     | STANDARD     | false | P1 | NEW@2026-12-31T22:00 | - | 2026-12-31T22:00 | 2026-12-31T22:30 | RUNNING  | 2027-01-01T02:00 | RUNNING  | 0   | 0
          # Clocks go back: 00:30 BST + 30 real min = 01:00 BST; + 4 real hours = 03:30 GMT (3 h of wall clock).
          F15 P1 24/7 over DST end                 | STANDARD     | false | P1 | NEW@2026-10-25T00:30 | - | 2026-10-25T00:30 | 2026-10-25T01:00 | RUNNING  | 2026-10-25T03:30 | RUNNING  | 0   | 0
          # Clocks go forward: 00:30 GMT + 30 real min = 02:00 BST; + 4 real hours = 05:30 BST (5 h of wall clock).
          F16 P1 24/7 over DST start               | STANDARD     | false | P1 | NEW@2027-03-28T00:30 | - | 2027-03-28T00:30 | 2027-03-28T02:00 | RUNNING  | 2027-03-28T05:30 | RUNNING  | 0   | 0
          # Business hours follow local time across the DST change: Fri 30 BST, Mon 08:30 GMT +210 = 12:00.
          F17 P3 business hours over DST end       | STANDARD     | false | P3 | NEW@2026-10-23T17:00 | - | 2026-10-23T17:00 | 2026-10-26T12:00 | RUNNING  | 2026-10-28T17:00 | RUNNING  | 0   | 0
          # Wednesday has no hours: Tue 90, skip Wed, Thu +150 = 11:00; 90 + Thu 540 + Fri 540 + Mon 450 = 16:00.
          F18 P3 day with no hours                 | NO_WEDNESDAY | false | P3 | NEW@2026-11-24T16:00 | - | 2026-11-24T16:00 | 2026-11-26T11:00 | RUNNING  | 2026-11-30T16:00 | RUNNING  | 0   | 0
          # Overnight gap: Mon 30, Tue +30 = 09:00; 30 + 450 = Tue 16:00.
          F19 P2 overnight gap                     | STANDARD     | false | P2 | NEW@2026-11-23T17:00 | - | 2026-11-23T17:00 | 2026-11-24T09:00 | RUNNING  | 2026-11-24T16:00 | RUNNING  | 0   | 0
          # Created before opening: everything starts 08:30 the same day.
          F20 P3 created before opening            | STANDARD     | false | P3 | NEW@2026-11-24T06:00 | - | 2026-11-24T06:00 | 2026-11-24T12:30 | RUNNING  | 2026-11-26T17:30 | RUNNING  | 0   | 0
          # Pending without a reply pauses first response too: 30 + 180 = 210 used; paused 480 + 540 + 60 = 1,080 = 2 days.
          F21 P3 pause before any reply            | STANDARD     | false | P3 | NEW@2026-11-23T09:00, PENDING_ON_CLIENT@2026-11-23T09:30, OPEN@2026-11-25T09:30 | - | 2026-11-25T12:30 | 2026-11-25T13:00 | RUNNING  | 2026-11-30T09:00 | RUNNING  | 210 | 1080
          # Still paused at the evaluation instant: no resolution due time; paused 450 Mon + 210 Tue.
          F22 P3 paused now has no due time        | STANDARD     | false | P3 | NEW@2026-11-23T09:00, PENDING_ON_CLIENT@2026-11-23T10:00 | 2026-11-23T10:00 | 2026-11-24T12:00 | 2026-11-23T13:00 | MET      | -                | PAUSED   | 60  | 660
          # Resolved after 360 of 1,620: met; reply at 09:15 met (due projected 09:15 + 225 = 13:00).
          F23 P3 resolved within target            | STANDARD     | false | P3 | NEW@2026-11-23T09:00, RESOLVED@2026-11-23T15:00 | 2026-11-23T09:15 | 2026-11-25T09:00 | 2026-11-23T13:00 | MET      | -                | MET      | 360 | 0
          # Closed after Resolved stays met; Resolved and Closed time is neither used nor paused.
          F24 P4 resolved then closed              | STANDARD     | false | P4 | NEW@2026-11-23T09:00, RESOLVED@2026-11-24T09:00, CLOSED@2026-11-30T09:00 | 2026-11-23T11:00 | 2026-11-30T10:00 | 2026-11-24T09:00 | MET      | -                | MET      | 540 | 0
          # Short Friday: 150 Fri, Mon +390 = 15:00; 10 days = 5,400 ends Mon 14 Dec 16:00 (Mon-Thu 540, Fri 240).
          F25 P4 short Friday                      | SHORT_FRIDAY | false | P4 | NEW@2026-11-27T10:00 | - | 2026-11-27T10:00 | 2026-11-30T15:00 | RUNNING  | 2026-12-14T16:00 | RUNNING  | 0   | 0
          # P1 pauses on Pending on client too, in clock minutes: used 10 + 50, paused 120, due 13:00 + 180.
          F26 P1 24/7 with a pause                 | STANDARD     | false | P1 | NEW@2026-11-21T10:00, PENDING_ON_CLIENT@2026-11-21T10:10, OPEN@2026-11-21T12:10 | 2026-11-21T10:10 | 2026-11-21T13:00 | 2026-11-21T10:30 | MET      | 2026-11-21T16:00 | RUNNING  | 60  | 120
          # Resolved without any public reply: first response stopped (neither met nor breached).
          F27 P2 resolved without a reply          | STANDARD     | false | P2 | NEW@2026-11-23T09:00, RESOLVED@2026-11-23T09:20 | - | 2026-11-23T12:00 | -                | STOPPED  | -                | MET      | 20  | 0
          # Late reply: 90 used > 60 is breached even though it was answered.
          F28 P2 late first response               | STANDARD     | false | P2 | NEW@2026-11-23T09:00 | 2026-11-23T10:30 | 2026-11-23T11:00 | 2026-11-23T10:00 | BREACHED | 2026-11-23T17:00 | RUNNING  | 120 | 0
          """)
  void handCalculatedCase(
      String name,
      String calendarName,
      boolean vendorPauses,
      Priority priority,
      String historySpec,
      String responded,
      String asOf,
      String expectedFrDue,
      TargetState expectedFrState,
      String expectedResDue,
      TargetState expectedResState,
      long expectedResUsedMinutes,
      long expectedPausedMinutes) {
    var changes = history(historySpec);
    var input =
        new SlaInput(
            changes.get(0).at(),
            priority,
            SlaPolicy.defaults().withPendingOnVendorPauses(vendorPauses),
            calendar(calendarName),
            changes,
            "-".equals(responded) ? null : at(responded));

    SlaResult result = SlaCalculator.calculate(input, at(asOf));

    SoftAssertions soft = new SoftAssertions();
    soft.assertThat(local(result.firstResponse().dueAt())).as("FR due").isEqualTo(expectedFrDue);
    soft.assertThat(result.firstResponse().state()).as("FR state").isEqualTo(expectedFrState);
    soft.assertThat(local(result.resolution().dueAt())).as("res due").isEqualTo(expectedResDue);
    soft.assertThat(result.resolution().state()).as("res state").isEqualTo(expectedResState);
    soft.assertThat(result.resolution().usedMinutes())
        .as("res used")
        .isEqualTo(expectedResUsedMinutes);
    soft.assertThat(result.pausedMinutes()).as("paused").isEqualTo(expectedPausedMinutes);
    soft.assertAll();
    assertThat(result.priority()).isEqualTo(priority);
  }
}
