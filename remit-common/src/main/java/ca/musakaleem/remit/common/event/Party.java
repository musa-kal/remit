package ca.musakaleem.remit.common.event;

/**
 * Debtor or creditor of a payment: name plus Canadian institution, transit and account numbers.
 *
 * @param name account holder name, 1 to 70 characters
 * @param institution three digit institution number
 * @param transit five digit transit (branch) number
 * @param account account number, 7 to 12 digits
 */
public record Party(String name, String institution, String transit, String account) {}
