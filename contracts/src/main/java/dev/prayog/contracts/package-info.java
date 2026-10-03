/**
 * Types shared by every Prayog service. They mirror the JSON Schemas in {@code contracts/schemas/}; tests keep the two
 * in sync.
 *
 * <p>Units, everywhere: prices are integer paise ({@code long}), quantities are shares ({@code long}), sim time is
 * microseconds since the Unix epoch. {@code 0} means "none" for a price or an order ID; in JSON the field is omitted.
 * See docs/adr/0002-contracts-and-domain-types.md.
 *
 * <p>This module has no runtime dependencies, so the matching engine can use it without pulling in any library.
 */
package dev.prayog.contracts;
