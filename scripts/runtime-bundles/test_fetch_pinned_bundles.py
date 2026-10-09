import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

source = Path(__file__).with_name("fetch-pinned-bundles.py")
spec = importlib.util.spec_from_file_location("bundles", source)
bundles = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundles)


class ArchiveVerificationTest(unittest.TestCase):
    def setUp(self):
        directory = source.resolve().parents[2] / "dist/runtime-bundles/sources"
        directory.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=directory)
        self.path = Path(self.temp.name) / "archive"
        self.path.write_bytes(b"original")

    def tearDown(self):
        self.temp.cleanup()

    def metadata(self, algorithm):
        return {algorithm: hashlib.new(algorithm, b"original").hexdigest(), "compressedBytes": 8}

    def test_valid_sha256(self):
        self.assertTrue(bundles.verify_archive(self.path, self.metadata("sha256")))

    def test_same_size_corruption(self):
        self.path.write_bytes(b"tampered")
        self.assertFalse(bundles.verify_archive(self.path, self.metadata("sha256")))

    def test_truncated_archive(self):
        self.path.write_bytes(b"short")
        self.assertFalse(bundles.verify_archive(self.path, self.metadata("sha256")))

    def test_pinned_codex_sha512(self):
        self.assertTrue(bundles.verify_archive(self.path, self.metadata("sha512")))


if __name__ == "__main__":
    unittest.main()
