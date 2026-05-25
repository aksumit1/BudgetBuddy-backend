/**
 * LLM-augmented insights services. Each class in this package is
 * feature-flagged off by default and only activates when its specific
 * Spring property is set ({@code app.insights.ai-*.enabled=true}).
 * Mirrors the {@link com.budgetbuddy.service.pdf.ai} package — same
 * activation gate, same Anthropic HTTP pattern, same per-session cache,
 * same graceful-degradation contract (null result on any failure;
 * caller falls back to the deterministic path).
 *
 * <p>Design principles:
 * <ul>
 *   <li>Optional augmentation — never a hard dependency.</li>
 *   <li>Low-confidence outputs return null, not "best guess" text. False
 *       positives on financial advice erode trust faster than missing
 *       data.</li>
 *   <li>Per-session cache prevents re-asking the LLM for the same input
 *       within an app lifetime.</li>
 *   <li>Short timeouts (8s) so a failing LLM doesn't pin the request
 *       thread.</li>
 * </ul>
 */
package com.budgetbuddy.service.insights.ai;
