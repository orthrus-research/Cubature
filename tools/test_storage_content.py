import copy
import gzip
import hashlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
import uuid
import nbtlib as n
from inspect_cubic_lab_world import read_regionlib_entries
from inspect_storage_content import check_cube


class StorageContentTest(unittest.TestCase):
    def fixture(self):
        uid=uuid.UUID('12345678-1234-5678-8123-456789012345');payload=b'checked payload'
        w=dict(cube=[-1,-7,-2],entityUuid=str(uid),name='fixture',chest=[-11,-107,-27],delayedTick=[-9,-107,-27],capabilityPayloadSha256=hashlib.sha256(payload).hexdigest())
        def position(values):return {k:n.Int(v) for k,v in zip(('x','y','z'),values)}
        level=n.Compound(position(w['cube'])|{
            'ForgeCaps':n.Compound({'cubiclab:storage_contract':n.Compound({'revision':n.Int(37),'payload':n.ByteArray(list(payload))})}),
            'Entities':n.List[n.Compound]([n.Compound({'UUIDMost':n.Long(uid.int>>64),'UUIDLeast':n.Long((uid.int&((1<<64)-1))-(1<<64)),'id':n.String('minecraft:armor_stand'),'CustomName':n.String('fixture'),'NoGravity':n.Byte(1)})]),
            'TileEntities':n.List[n.Compound]([n.Compound(position(w['chest'])|{'id':n.String('minecraft:chest'),'CustomName':n.String('fixture'),'Items':n.List[n.Compound]([n.Compound({'Slot':n.Byte(0),'Count':n.Byte(17),'id':n.String('minecraft:diamond')})])})]),
            'TileTicks':n.List[n.Compound]([n.Compound(position(w['delayedTick'])|{'i':n.String('minecraft:stone'),'p':n.Int(3),'t':n.Int(999999)})])})
        return level,w

    def test_content_and_independent_corruption_detectors(self):
        level,w=self.fixture();check_cube(level,w)
        for change in [lambda v:v['Entities'].clear(),lambda v:v['TileEntities'][0]['Items'][0].update(Count=n.Byte(16)),
                       lambda v:v['TileTicks'][0].update(p=n.Int(0)),lambda v:v['ForgeCaps']['cubiclab:storage_contract'].update(revision=n.Int(36))]:
            bad=copy.deepcopy(level);change(bad)
            with self.assertRaises(ValueError):check_cube(bad,w)

    def test_overflow_is_explicit_bounded_and_detects_corruption(self):
        level,_=self.fixture();data=io.BytesIO();n.File({'Level':level}).write(data)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'-1.-1.-1.3dr';header=bytearray(16384);path.write_bytes(header)
            ext=path.with_name(path.name+'.ext');ext.mkdir();entry=ext/'3';entry.write_bytes(gzip.compress(data.getvalue()))
            with self.assertRaises(ValueError):list(read_regionlib_entries(path,[3],4096))
            rows=list(read_regionlib_entries(path,[3],4096,allow_overflow=True));self.assertEqual(level,rows[0][1]);self.assertIsNotNone(rows[0][2]['externalFile'])
            with self.assertRaises(ValueError):list(read_regionlib_entries(path,[3],4096,64,allow_overflow=True))
            entry.write_bytes(entry.read_bytes()[:-3])
            with self.assertRaises((EOFError,OSError,ValueError)):list(read_regionlib_entries(path,[3],4096,allow_overflow=True))
            entry.unlink()
            self.assertEqual([],list(read_regionlib_entries(path,[3],4096,allow_overflow=True)))
            struct.pack_into('>I',header,12,0xffffffff);path.write_bytes(header)
            with self.assertRaises(ValueError):list(read_regionlib_entries(path,[3],4096,allow_overflow=True))

if __name__=='__main__':unittest.main()
