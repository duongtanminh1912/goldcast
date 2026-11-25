import type { MarketSummary } from "@/lib/types";

/**
 * Daily USD/VND rate and unit constants from the market summary. The minute price is
 * converted with them in the browser, so the VND figure moves with every minute quote.
 * Lives outside LiveWorldGold: a server page cannot call a function exported by a
 * "use client" module.
 */
export type LiveGoldConversion = {
  vndPerUsd: number;
  fxAsOf: string;
  troyOunceInGrams: number;
  taelInGrams: number;
};

/** Null when the summary has no exchange rate yet; the live card then shows USD only. */
export function liveConversion({ fx, conversion }: Pick<MarketSummary, "fx" | "conversion">): LiveGoldConversion | null {
  if (!fx || !conversion) return null;
  return {
    vndPerUsd: fx.vndPerUsd,
    fxAsOf: fx.asOf,
    troyOunceInGrams: conversion.troyOunceInGrams,
    taelInGrams: conversion.taelInGrams,
  };
}

/** Rounded to the nearest thousand VND: the inputs are not more precise than that. */
export function vndPerTael(usdPerOunce: number, conversion: LiveGoldConversion): number {
  const raw = (usdPerOunce * conversion.vndPerUsd / conversion.troyOunceInGrams) * conversion.taelInGrams;
  return Math.round(raw / 1000) * 1000;
}
