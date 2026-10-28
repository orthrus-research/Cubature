from pathlib import Path
import tempfile
import unittest
from lab_storage_evidence import retain_lifecycle,lifecycle_names,validate_trace,validate_complete_ticks

class StorageEvidenceTest(unittest.TestCase):
    def test_current_runs_require_complete_outer_ticks_without_relabeling_history(self):
        manifest={'labProperties':{'cubiclab.tickTimer':'complete-v1'}}
        with self.assertRaises(RuntimeError):validate_complete_ticks(manifest,{'serverTicks':{'count':10}})
        validate_complete_ticks({}, {'serverTicks':{'count':10}})
        server={'completeServerTickScope':'test','serverTicks':{'count':10},'completeServerTicks':{'count':10,'droppedCount':0}}
        validate_complete_ticks(manifest,server);server['completeServerTicks']['count']=9
        with self.assertRaises(RuntimeError):validate_complete_ticks(manifest,server)
    def test_trace_reconciles_counts_failures_and_censoring_without_summing_nested_time(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'trace.csv').write_text('operation,owner,thread,startOffsetNanos,endOffsetNanos,bytes,success\n0,0,1,10,30,0,1\n1,0,1,12,24,0,0\n')
            value={'trace':{'chronologicalCsv':'trace.csv','totalCount':2,'storedCount':2,'droppedCount':0,'operations':{'batch':0,'force':1}},'timings':{'batch':{'count':1,'totalNanos':20},'force':{'count':1,'totalNanos':12}},'failures':{'force':1},'unfinishedSpansByOperation':{'completeServerTick':1}}
            result=validate_trace(value,root);self.assertEqual({'batch':20,'force':12},result['inclusiveNanos']);self.assertEqual({'completeServerTick':1},result['unfinished'])
            force=value['timings'].pop('force')
            with self.assertRaises(ValueError):validate_trace(value,root)
            value['timings']['force']=force;force['totalNanos']=13
            with self.assertRaises(ValueError):validate_trace(value,root)
            force['totalNanos']=12;value['trace']['operations']['force']=0
            with self.assertRaises(ValueError):validate_trace(value,root)
            value['trace']['operations']['force']=1
            value['trace']['droppedCount']=1
            with self.assertRaises(ValueError):validate_trace(value,root)
            value['trace']['droppedCount']=0;value['trace']['chronologicalCsv']='../trace.csv'
            with self.assertRaises(ValueError):validate_trace(value,root)

    def test_shutdown_evidence_only_copies_new_files_with_hashes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'cubiclab-lifecycle';source.mkdir();(source/'old.json').write_text('{}');before=lifecycle_names(root)
            (source/'new.json').write_text('{"normalReturn":true}');(source/'new.csv').write_text('sample\n1\n');evidence=root/'evidence';evidence.mkdir()
            rows=retain_lifecycle(root,before,evidence);self.assertEqual(2,len(rows));self.assertTrue(all(len(r['sha256'])==64 for r in rows));self.assertFalse((evidence/'storage-lifecycle/old.json').exists())

if __name__=='__main__':unittest.main()
