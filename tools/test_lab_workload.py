"""Input/provenance contracts; native timing and terrain are separate evidence."""
import copy
from pathlib import Path
import tempfile
import unittest
import lab_workload as w
import cubic_lab


def workload():
    return {'schema':1,'id':'contract','seed':-(2**63),'camera':[-.5,80,-16.5,170,20],
        'scenarios':{'write':['full-view','route']},'route':{'interpolation':'linear','waypoints':[
            {'pose':[-.5,80,-16.5,170,20],'ticks':40},{'pose':[31.5,96,-48.5,-170,30],'ticks':80}]}}


class WorkloadTest(unittest.TestCase):
    def test_spectator_lifecycle_requires_complete_order_and_wall_time_definition(self):
        value=workload();value.pop('route');value['scenarios']={'write':['full-view','spectator-flight','lifecycle-settle','lifecycle-save-purge']}
        value['spectatorFlight']={'durationSeconds':60,'blocksPerSecond':16,'settleSeconds':20}
        w.validate(value)
        for key,bad in [('durationSeconds',1200),('blocksPerSecond',1),('settleSeconds',0)]:
            changed=copy.deepcopy(value);changed['spectatorFlight'][key]=bad
            with self.assertRaises(ValueError):w.validate(changed)
        value['scenarios']['write']=['spectator-flight','full-view','lifecycle-settle','lifecycle-save-purge']
        with self.assertRaisesRegex(ValueError,'write order'):w.validate(value)
    def test_two_worlds_requires_saved_write_fixture(self):
        value=workload();value['scenarios']={'write':['generate','save','storage-two-worlds']};w.validate(value)
        for scenarios in ({'write':['storage-two-worlds']},{'write':['generate','save'],'read':['storage-two-worlds']},
                          {'write':['generate','storage-two-worlds','save']}):
            value['scenarios']=scenarios
            with self.assertRaisesRegex(ValueError,'write-phase'):w.validate(value)
    def test_storage_content_requires_generation_and_matching_write(self):
        value=workload();value['scenarios']={'write':['storage-content-write'],'read':['storage-content-read']}
        with self.assertRaisesRegex(ValueError,'generation'):w.validate(value)
        value['scenarios']['write']=['generate','storage-content-write'];w.validate(value)
        value['scenarios']['write']=['generate']
        with self.assertRaisesRegex(ValueError,'matching write'):w.validate(value)
    def test_real_templates_validate_and_preserve_full_height_fixture_counts(self):
        for path in (Path(__file__).resolve().parents[1]/'lab/benchmarks/workloads').glob('*.json'): w.read(path)
        self.assertEqual(len(w.fixture_columns())*32,1024)
        value=workload();value['fixture']={'originColumn':[-64,-128],'sizeColumns':[1,1],'cohortOffset':[8,0]}
        self.assertEqual(w.fixture_columns(w.validate(value)),{(-64,-128),(-56,-128)})

    def test_unknown_fields_invalid_poses_ranges_and_overlapping_cohorts_fail(self):
        changes=[('unknown',1),('seed',2**63),('seed',True),('camera',[0,384,0,0,0]),
            ('camera',[float('nan'),80,0,0,0]),('warmupTicks',-1),('timeoutSeconds',0),
            ('fixture',{'sizeColumns':[4,4],'cohortOffset':[3,3]}),('fixture',{'requirement':'GET_CACHED'})]
        for key,value in changes:
            data=workload();data[key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):w.validate(data)

    def test_missing_routes_duplicate_scenarios_and_unprepared_reload_fail(self):
        for mutate in [lambda v:v.pop('route'),lambda v:v['scenarios'].update(write=['route','route']),
                lambda v:v['scenarios'].update(read=['load-sync'])]:
            value=workload();mutate(value)
            with self.assertRaises(ValueError):w.validate(value)
        value=workload();value['route']['waypoints'][0]['ticks']=12000
        with self.assertRaises(ValueError):w.validate(value)

    def test_canonical_inputs_are_identical_on_both_sides_and_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);a=w.install(workload(),root/'server');b=w.install(workload(),root/'client')
            self.assertEqual(a,b);(root/'server/server.properties').write_text('level-seed='+str(-(2**63))+'\n')
            manifest={'server':str(root/'server'),'client':str(root/'client'),'benchmarkWorkload':a,'labProperties':{'cubiclab.workload':w.RELATIVE_PATH}};w.verify(manifest)
            path=root/'client'/w.RELATIVE_PATH;path.write_bytes(path.read_bytes()+b' ')
            with self.assertRaisesRegex(RuntimeError,'changed'):w.verify(manifest)

    def test_native_identity_seed_and_route_endpoint_are_required(self):
        value=workload();descriptor=w.identity(value,b'test');manifest={'benchmarkWorkload':dict(descriptor,value=value)}
        server={'benchmarkWorkload':descriptor,'worldSeed':value['seed'],'scenario':'route','routeObservationTicks':120,'routeTrace':[{'tick':119}]}
        client={'benchmarkWorkload':descriptor,'finalCameraPose':value['route']['waypoints'][-1]['pose'],'routeCameraSamples':6,'overlayObservationAvailable':True,'overlayWaitRequired':True}
        w.validate_capture(manifest,server,client)
        for key,bad in [('finalCameraPose',[0,0,0,0,0]),('routeCameraSamplesDropped',1),('benchmarkWorkload',None),('framesWithOverlay',1),('overlayProbeFailure','unavailable')]:
            changed=dict(client);changed[key]=bad
            with self.subTest(key=key),self.assertRaises(RuntimeError):w.validate_capture(manifest,server,changed)
        with self.assertRaises(RuntimeError):w.validate_capture(manifest,dict(server,worldSeed=1),client)

    def test_missing_phase_and_route_override_fail_before_native_priming(self):
        import argparse
        args=argparse.Namespace(phase='read',scenarios=None)
        manifest={'benchmarkWorkload':{'value':workload()},'seed':workload()['seed']}
        with self.assertRaises(ValueError):cubic_lab.selected_scenarios(args,manifest)
        args.scenarios='route'
        with self.assertRaises(ValueError):cubic_lab.selected_scenarios(args,{})


if __name__=='__main__':unittest.main()
