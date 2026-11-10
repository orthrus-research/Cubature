"""Dependency publication on legacy Windows paths; no network or native JVM is launched."""
import builtins
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import errno
import hashlib
import io
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock

import lab_instance


@contextmanager
def legacy_path_writes():
    """Keep real filesystem writes/copies while enforcing legacy MAX_PATH."""
    writes = []
    builtin_open, path_open = builtins.open, Path.open
    copy = lab_instance.shutil.copy2

    def check(path, mode):
        if not isinstance(path, int) and any(flag in mode for flag in 'wax'):
            absolute = os.path.abspath(os.fspath(path))
            writes.append(Path(absolute))
            if len(absolute) >= 260:
                raise OSError(errno.ENOENT, 'Legacy Windows path limit', absolute)

    def open_file(path, mode='r', *args, **kwargs):
        check(path, mode)
        return builtin_open(path, mode, *args, **kwargs)

    def open_path(path, mode='r', *args, **kwargs):
        check(path, mode)
        return path_open(path, mode, *args, **kwargs)

    def copy_file(source, target, *args, **kwargs):
        check(target, 'wb')
        return copy(source, target, *args, **kwargs)

    with mock.patch('builtins.open', open_file), mock.patch.object(Path, 'open', open_path), mock.patch.object(lab_instance.shutil, 'copy2', copy_file):
        yield writes


class LabFetchTest(unittest.TestCase):
    @staticmethod
    def digest(data, algorithm='sha1'):
        return hashlib.new(algorithm, data).hexdigest()

    def long_target(self, root):
        filename = 'dependency-library.jar'
        padding = 227 - len(str(root / filename)) - 1
        self.assertGreater(padding, 0)
        target = root / ('p' * padding) / filename
        target.parent.mkdir()
        self.assertEqual(len(str(target)), 227)
        # A legal destination must not need room for another UUID suffix after its filename.
        suffixed = target.with_name(target.name + '.' + '0' * 32 + '.partial')
        self.assertEqual(len(str(suffixed)), 268)
        return target

    def test_cached_dependency_publishes_under_legacy_path_limit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            target = self.long_target(root)
            candidate = root / 'candidate.jar'
            data = b'reviewed cached dependency'
            candidate.write_bytes(data)
            os.utime(candidate, (1700000000, 1700000000))
            with legacy_path_writes() as writes, mock.patch.object(lab_instance.urllib.request, 'urlopen') as fetch:
                result = lab_instance.fetch('https://example.invalid/library.jar', target,
                                            self.digest(data), candidates=[candidate])
            fetch.assert_not_called()
            self.assertEqual(result, target)
            self.assertEqual(target.read_bytes(), data)
            self.assertEqual(target.stat().st_mtime_ns, candidate.stat().st_mtime_ns)
            self.assertTrue(writes)
            self.assertTrue(all(path.parent == target.parent and len(str(path)) < 260 for path in writes))
            self.assertEqual(list(target.parent.glob('*.partial')), [])

    def test_fetched_dependency_publishes_under_legacy_path_limit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            target = self.long_target(root)
            target.write_bytes(b'previous invalid cache')
            data = b'reviewed fetched dependency'
            with legacy_path_writes() as writes, mock.patch.object(
                    lab_instance.urllib.request, 'urlopen', return_value=io.BytesIO(data)) as fetch:
                result = lab_instance.fetch('https://example.invalid/library.jar', target,
                                            self.digest(data, 'sha256'), algorithm='sha256')
            fetch.assert_called_once()
            self.assertEqual(result, target)
            self.assertEqual(target.read_bytes(), data)
            self.assertTrue(writes)
            self.assertTrue(all(path.parent == target.parent and len(str(path)) < 260 for path in writes))
            self.assertEqual(list(target.parent.glob('*.partial')), [])

    def test_wrong_candidate_and_http_hash_do_not_replace_existing_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target, candidate = root / 'library.jar', root / 'candidate.jar'
            target.write_bytes(b'original target')
            candidate.write_bytes(b'wrong cached bytes')
            with mock.patch.object(lab_instance.urllib.request, 'urlopen', return_value=io.BytesIO(b'wrong HTTP bytes')) as fetch:
                with self.assertRaisesRegex(RuntimeError, 'Checksum mismatch'):
                    lab_instance.fetch('https://example.invalid/library.jar', target,
                                       self.digest(b'expected bytes'), candidates=[candidate])
            fetch.assert_called_once()
            self.assertEqual(target.read_bytes(), b'original target')
            self.assertEqual(candidate.read_bytes(), b'wrong cached bytes')
            self.assertEqual(set(root.iterdir()), {target, candidate})

    def test_valid_target_needs_no_copy_or_request(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'library.jar'
            data = b'already verified'
            target.write_bytes(data)
            with mock.patch.object(lab_instance.urllib.request, 'urlopen') as fetch, mock.patch.object(lab_instance.shutil, 'copy2') as copy:
                self.assertEqual(lab_instance.fetch('https://example.invalid/library.jar', target,
                                                  self.digest(data)), target)
            fetch.assert_not_called()
            copy.assert_not_called()
            self.assertEqual(list(target.parent.iterdir()), [target])

    def test_concurrent_publications_in_one_directory_own_distinct_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payloads = {'https://example.invalid/a': b'first', 'https://example.invalid/b': b'second'}
            ready, replacements = threading.Barrier(2), []
            replace = Path.replace

            def publish(path, target):
                self.assertEqual(path.parent, target.parent)
                replacements.append(path)
                ready.wait(timeout=5)
                return replace(path, target)

            with mock.patch.object(lab_instance.urllib.request, 'urlopen', side_effect=lambda request, **kwargs: io.BytesIO(payloads[request.full_url])), mock.patch.object(Path, 'replace', publish), ThreadPoolExecutor(max_workers=2) as workers:
                tasks = [workers.submit(lab_instance.fetch, url, root / (str(index) + '.jar'), self.digest(data))
                         for index, (url, data) in enumerate(payloads.items())]
                for task, data in zip(tasks, payloads.values()):
                    self.assertEqual(task.result(timeout=10).read_bytes(), data)
            self.assertEqual(len(set(replacements)), 2)
            self.assertEqual(list(root.glob('*.partial')), [])

    def test_failed_install_does_not_modify_previous_target_or_other_partials(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target, unrelated = root / 'library.jar', root / 'other.partial'
            target.write_bytes(b'previous target')
            unrelated.write_bytes(b'other download')
            data = b'new valid bytes'
            with mock.patch.object(lab_instance.urllib.request, 'urlopen', return_value=io.BytesIO(data)), mock.patch.object(Path, 'replace', side_effect=PermissionError('publication blocked')):
                with self.assertRaisesRegex(PermissionError, 'publication blocked'):
                    lab_instance.fetch('https://example.invalid/library.jar', target, self.digest(data))
            self.assertEqual(target.read_bytes(), b'previous target')
            self.assertEqual(unrelated.read_bytes(), b'other download')


if __name__ == '__main__':
    unittest.main()
