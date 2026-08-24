/**
 * Compatibility boundary for Delta Kernel 4.0.1.
 *
 * <p>FIQ uses two Kernel implementation details because the 4.0.1 public API does not expose the
 * equivalent facts:
 *
 * <ul>
 *   <li>{@code InternalScanFileUtils} reads AddFile deletion-vector and file-stat fields.
 *   <li>{@code ClusteringMetadataDomain} decodes liquid-clustering domain metadata.
 * </ul>
 *
 * No other FIQ package may import {@code io.delta.kernel.internal}. This adapter is covered by the
 * qualified Delta 4.0.1 compatibility tests and must be reviewed before qualifying another Delta
 * runtime.
 */
package io.fiq.delta.compatibility.delta401;
