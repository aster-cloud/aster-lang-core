/**
 * Parser internals: ANTLR-generated lexer / parser plus a small custom
 * layer for indent/dedent + error recovery.
 *
 * <p><b>API stability</b>: classes here are {@link aster.core.Internal @Internal}
 * by convention even when declared {@code public}. {@code AsterCustomLexer}
 * and {@code AstBuilder} exist as public because:
 * <ul>
 *   <li>ANTLR generates a {@code public} lexer base class we have to extend</li>
 *   <li>{@link aster.core.parser.AstBuilder} is invoked from downstream
 *       modules (aster-api) and so must be reachable</li>
 * </ul>
 *
 * <p>Downstream consumers should depend on
 * {@code aster.core.ast.Module} + the canonicalizer's published API, NOT on
 * the parser internals. Breaking changes here will not bump the major
 * version.
 *
 * @see aster.core.Internal
 */
package aster.core.parser;
