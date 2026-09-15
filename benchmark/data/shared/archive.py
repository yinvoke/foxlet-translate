"""Resolve archive paths for one version directory.

Measurement inputs that several versions share — collector programs and
corpora — are stored once under ``benchmark/data/shared/``. Each version's
``index.json`` maps the name its own records use onto the stored file, so a
checker keeps verifying exactly the bytes it always did.

Archived records are never rewritten. Where identical payloads are stored
once, a checker locates the stored copy through the sha256 the record already
carries rather than through its file name.
"""
import hashlib
import json
from pathlib import Path


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class Archive:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.index = json.loads((self.root / 'index.json').read_text(encoding='utf-8'))
        self.raw = self.root / 'raw'

    def path(self, rel):
        """A path inside this version's own raw/ tree."""
        return self.raw / rel

    def read(self, rel):
        return json.loads(self.path(rel).read_text(encoding='utf-8'))

    def _shared(self, section, name):
        try:
            target = self.index[section][name]
        except KeyError as error:
            raise KeyError(f'{section}: {name} is not mapped in '
                           f'{self.root.name}/index.json') from error
        return (self.root / target).resolve()

    def collector(self, name):
        return self._shared('collectors', name)

    def corpus(self, name):
        return self._shared('corpora', name)

    def external(self, name):
        """A file kept in another version's archive; the index pins its sha256."""
        entry = self.index['external'][name]
        path = (self.root / entry['path']).resolve()
        assert digest(path) == entry['sha256'], f'{name} changed in {entry["path"]}'
        return path

    def by_content(self, directory):
        """Map sha256 -> stored file, for directories that hold deduplicated payloads."""
        return {digest(p): p for p in Path(directory).iterdir() if p.is_file()}


def open_archive(root):
    return Archive(root)
