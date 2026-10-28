from pathlib import Path
import subprocess
import tempfile
import unittest
from lab_source_snapshot import freeze,verify

class SourceSnapshotTest(unittest.TestCase):
    def test_tracks_uncommitted_addition_change_and_deletion_without_builds_or_docs(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'repo';root.mkdir();subprocess.run(['git','init','-q',str(root)],check=True)
            (root/'src').mkdir();source=root/'src/A.java';source.write_text('class A {}')
            subprocess.run(['git','-C',str(root),'add','.'],check=True)
            subprocess.run(['git','-C',str(root),'-c','user.name=Fixture','-c','user.email=fixture@invalid','commit','-qm','fixture'],check=True)
            (root/'tools').mkdir();tool=root/'tools/extra.py';tool.write_text('print(1)')
            (root/'lab/build').mkdir(parents=True);(root/'lab/build/out.java').write_text('ignored')
            result=freeze(root,Path(temp)/'snapshot');self.assertEqual({'src/A.java','tools/extra.py'},set(result['files']));self.assertEqual([],verify(result))
            source.write_text('class A { int changed; }');tool.unlink();(root/'src/B.java').write_text('class B {}')
            self.assertEqual(['src/A.java','src/B.java','tools/extra.py'],verify(result));self.assertEqual(['src/A.java','src/B.java'],verify(result,['src/']))

if __name__=='__main__':unittest.main()
