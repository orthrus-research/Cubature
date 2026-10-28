package io.github.opencubicchunks.cubicchunks.api.world.storage;

/**
 * Opt-in close contract for storage that retains ownership of failed releases.
 *
 * <p>After close begins, callers must not issue reads, writes, enumeration or flushes, including after
 * a failed close. They may explicitly retry {@link #close()}. A retry releases only resources still
 * owned by this storage; a successful or repeated successful close is idempotent. A failure must not
 * erase the ownership needed for a later retry or be reported as a successful release.
 *
 * <p>This capability does not promise that arbitrary writes remain recoverable after a failed close.
 * The caller must complete its durability barrier before beginning close. Providers that implement
 * only {@link ICubicStorage} retain that interface's single-attempt disposal contract.
 */
public interface IRetryableCloseStorage extends ICubicStorage {
}
