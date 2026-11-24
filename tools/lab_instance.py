"""What a lab instance's inputs are before a native launch: the hash of every file its JVMs read as configuration.

Workbench builds instances (lab.build) and checks them against their receipts; the lab assembles none (lab_session)."""
from pathlib import Path
from lab_files import sha


def record_inputs(manifest):
    inputs = {}
    for side in ['server', 'client']:
        directory = Path(manifest[side]); paths = []
        for folder in ['config', 'groovy', 'local', 'resources', 'resourcepacks']:
            paths.extend(path for path in (directory / folder).rglob('*') if path.is_file())
        paths.extend(path for path in [directory / 'options.txt', directory / 'server.properties', directory / 'user_jvm_args.txt'] if path.is_file())
        inputs[side] = {path.relative_to(directory).as_posix(): sha(path) for path in sorted(paths)}
    return inputs
