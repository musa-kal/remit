package ca.musakaleem.remit.intake.validation;

import ca.musakaleem.remit.common.IdempotencyKeys;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.Party;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.config.RoutingRulesProperties;
import ca.musakaleem.remit.intake.config.RoutingRulesProperties.ChannelRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Applies every field rule and channel rule from spec 3.3 to one row. */
@Component
public class InstructionValidator {

  /** Longest allowed debtor or creditor name. */
  public static final int MAX_NAME_LENGTH = 70;

  /** Longest allowed remittance information. */
  public static final int MAX_REMITTANCE_LENGTH = 140;

  /** How far ahead of the business date a value date may be. */
  public static final int MAX_VALUE_DATE_DAYS_AHEAD = 30;

  private static final Pattern INSTRUCTION_REF = Pattern.compile("[A-Za-z0-9-]{1,35}");
  private static final Pattern INSTITUTION = Pattern.compile("\\d{3}");
  private static final Pattern TRANSIT = Pattern.compile("\\d{5}");
  private static final Pattern ACCOUNT = Pattern.compile("\\d{7,12}");
  // At most 9 integer digits and 2 decimals, so at most 999,999,999.99; no sign, no exponent.
  private static final Pattern AMOUNT = Pattern.compile("\\d{1,9}(\\.\\d{1,2})?");
  private static final Set<String> CURRENCIES = Set.of("CAD", "USD");
  private static final DateTimeFormatter VALUE_DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

  private final RoutingRulesProperties routingRules;

  /**
   * Creates the validator.
   *
   * @param routingRules channel limits from configuration
   */
  public InstructionValidator(RoutingRulesProperties routingRules) {
    this.routingRules = routingRules;
  }

  /**
   * Validates a row against every rule. Uniqueness of instruction_ref within the file is checked by
   * the caller, which knows the other rows.
   *
   * @param row the row
   * @param businessDate today's date in America/Toronto
   * @return every failed rule in declaration order, empty when the row is valid
   */
  public List<ReasonCode> validate(RawInstruction row, LocalDate businessDate) {
    if (row.malformed()) {
      return List.of(ReasonCode.MALFORMED_ROW);
    }
    Set<ReasonCode> reasons = EnumSet.noneOf(ReasonCode.class);

    check(
        reasons,
        matches(INSTRUCTION_REF, row.field(Column.INSTRUCTION_REF)),
        ReasonCode.INVALID_INSTRUCTION_REF);
    check(reasons, validName(row.field(Column.DEBTOR_NAME)), ReasonCode.INVALID_NAME);
    check(reasons, validName(row.field(Column.CREDITOR_NAME)), ReasonCode.INVALID_NAME);
    check(
        reasons,
        matches(INSTITUTION, row.field(Column.DEBTOR_INSTITUTION)),
        ReasonCode.INVALID_INSTITUTION);
    check(
        reasons,
        matches(INSTITUTION, row.field(Column.CREDITOR_INSTITUTION)),
        ReasonCode.INVALID_INSTITUTION);
    check(reasons, matches(TRANSIT, row.field(Column.DEBTOR_TRANSIT)), ReasonCode.INVALID_TRANSIT);
    check(
        reasons, matches(TRANSIT, row.field(Column.CREDITOR_TRANSIT)), ReasonCode.INVALID_TRANSIT);
    check(reasons, matches(ACCOUNT, row.field(Column.DEBTOR_ACCOUNT)), ReasonCode.INVALID_ACCOUNT);
    check(
        reasons, matches(ACCOUNT, row.field(Column.CREDITOR_ACCOUNT)), ReasonCode.INVALID_ACCOUNT);

    Optional<BigDecimal> amount = parseAmount(row.field(Column.AMOUNT));
    check(reasons, amount.isPresent(), ReasonCode.INVALID_AMOUNT);

    String currency = row.field(Column.CURRENCY);
    boolean currencyValid = CURRENCIES.contains(currency);
    check(reasons, currencyValid, ReasonCode.INVALID_CURRENCY);

    Optional<Channel> channel = parseChannel(row.field(Column.CHANNEL));
    check(reasons, channel.isPresent(), ReasonCode.INVALID_CHANNEL);

    check(
        reasons,
        validValueDate(row.field(Column.VALUE_DATE), businessDate),
        ReasonCode.INVALID_VALUE_DATE);
    check(
        reasons,
        row.field(Column.REMITTANCE_INFO).length() <= MAX_REMITTANCE_LENGTH,
        ReasonCode.REMITTANCE_TOO_LONG);

    channel.ifPresent(
        c -> {
          ChannelRule rule = routingRules.ruleFor(c);
          if (amount.isPresent() && rule.maxAmount() != null) {
            check(
                reasons,
                amount.get().compareTo(rule.maxAmount()) <= 0,
                ReasonCode.CHANNEL_LIMIT_EXCEEDED);
          }
          if (currencyValid && !rule.currencies().isEmpty()) {
            check(
                reasons,
                rule.currencies().contains(currency),
                ReasonCode.CHANNEL_CURRENCY_NOT_ALLOWED);
          }
        });
    return List.copyOf(reasons);
  }

  /**
   * Builds the event for a row that passed {@link #validate}.
   *
   * @param row a valid row
   * @param batchId batch the row belongs to
   * @param clientId client from the file name
   * @return the instruction with its idempotency key
   */
  public PaymentInstruction toInstruction(RawInstruction row, UUID batchId, String clientId) {
    String ref = row.field(Column.INSTRUCTION_REF);
    String remittance = row.field(Column.REMITTANCE_INFO);
    return new PaymentInstruction(
        IdempotencyKeys.forInstruction(clientId, ref),
        batchId,
        clientId,
        ref,
        new Party(
            row.field(Column.DEBTOR_NAME),
            row.field(Column.DEBTOR_INSTITUTION),
            row.field(Column.DEBTOR_TRANSIT),
            row.field(Column.DEBTOR_ACCOUNT)),
        new Party(
            row.field(Column.CREDITOR_NAME),
            row.field(Column.CREDITOR_INSTITUTION),
            row.field(Column.CREDITOR_TRANSIT),
            row.field(Column.CREDITOR_ACCOUNT)),
        new BigDecimal(row.field(Column.AMOUNT)).setScale(2),
        row.field(Column.CURRENCY),
        Channel.valueOf(row.field(Column.CHANNEL)),
        LocalDate.parse(row.field(Column.VALUE_DATE), VALUE_DATE),
        remittance.isEmpty() ? null : remittance,
        0);
  }

  private static void check(Set<ReasonCode> reasons, boolean valid, ReasonCode code) {
    if (!valid) {
      reasons.add(code);
    }
  }

  private static boolean matches(Pattern pattern, String value) {
    return pattern.matcher(value).matches();
  }

  private static boolean validName(String value) {
    return !value.isBlank() && value.length() <= MAX_NAME_LENGTH;
  }

  private static Optional<BigDecimal> parseAmount(String value) {
    if (!AMOUNT.matcher(value).matches()) {
      return Optional.empty();
    }
    BigDecimal amount = new BigDecimal(value);
    return amount.signum() > 0 ? Optional.of(amount) : Optional.empty();
  }

  private static Optional<Channel> parseChannel(String value) {
    for (Channel channel : Channel.values()) {
      if (channel.name().equals(value)) {
        return Optional.of(channel);
      }
    }
    return Optional.empty();
  }

  private static boolean validValueDate(String value, LocalDate businessDate) {
    try {
      LocalDate date = LocalDate.parse(value, VALUE_DATE);
      return !date.isBefore(businessDate)
          && !date.isAfter(businessDate.plusDays(MAX_VALUE_DATE_DAYS_AHEAD));
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
