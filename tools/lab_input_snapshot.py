"""Small immutable input proofs for comparing native captures, captured before launch."""
import hashlib
from pathlib import Path


INPUTS = (("server", "config/cubicchunks.cfg"), ("client", "config/cubicchunks.cfg"),
          ("server", "server.properties"), ("server", "user_jvm_args.txt"))


def retain_prelaunch_inputs(manifest, expected_hashes, evidence_dir):
    """Copy already-hashed bytes once; a changed input fails before native launch."""
    evidence = Path(evidence_dir).resolve()
    records = {"schema": 1, "files": {"server": {}, "client": {}}}
    inputs = list(INPUTS)
    if manifest.get('benchmarkWorkload'):
        inputs.extend((side,'config/cubiclab-workload.json') for side in ['server','client'])
    for side, name in inputs:
        source = (Path(manifest[side]) / name).resolve()
        data = source.read_bytes()
        actual = hashlib.sha256(data).hexdigest()
        if expected_hashes.get(side, {}).get(name) != actual:
            raise RuntimeError("Pre-launch input changed before snapshot: " + str(source))
        relative = Path("inputs") / side / name
        destination = evidence / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as stream:
            stream.write(data)
        records["files"][side][name] = {"path": relative.as_posix(), "sha256": actual,
                                       "bytes": len(data), "source": str(source)}
    return records
