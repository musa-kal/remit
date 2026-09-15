package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.common.MaskingUtil;
import ca.musakaleem.remit.common.event.Party;

/**
 * Debtor or creditor in an instruction response.
 *
 * @param name account holder name
 * @param institution institution number
 * @param transit transit number
 * @param account account number, masked to the last four digits unless shown to an ADMIN on the
 *     instruction detail
 */
public record PartyView(String name, String institution, String transit, String account) {

  /**
   * Maps a party.
   *
   * @param party the party, may be {@code null}
   * @param unmasked whether to show the full account number
   * @return the view, or {@code null} for {@code null}
   */
  public static PartyView of(Party party, boolean unmasked) {
    if (party == null) {
      return null;
    }
    return new PartyView(
        party.name(),
        party.institution(),
        party.transit(),
        unmasked ? party.account() : MaskingUtil.maskAccount(party.account()));
  }
}
