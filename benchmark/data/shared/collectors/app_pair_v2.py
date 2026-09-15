"""Pair two android-app-v1 runs without changing either version's eight cells."""
from pathlib import Path
import sys,subprocess,json,time,uuid,shlex,hashlib,re,importlib.util
root=Path.cwd()
quality_root=root/'quality/native'
if (quality_root/'manifest.json').exists():
 quality=json.loads((quality_root/'manifest.json').read_text())
 assert len(quality['runs'])==12
 assert quality['native_timing_report_sha256']==hashlib.sha256((quality_root.parent/'native-retry-1.json').read_bytes()).hexdigest()
 assert all(hashlib.sha256((quality_root/r['file']).read_bytes()).hexdigest()==r['file_sha256'] for r in quality['runs'])
else:
 subprocess.run([sys.executable, str(root/'${MEASUREMENT_DIR}/export_native_quality.py')], check=True)
out=root/'app';out.mkdir(exist_ok=False)
sys.path.insert(0,str(root/'tools/app-bench'));import run as collector
import bench_device
adb=['/opt/homebrew/share/android-commandlinetools/platform-tools/adb','-s','${DEVICE_SERIAL}']
versions=['v0.3.0','v0.5.0'];packages=dict(zip(versions,['io.github.yinvoker.bergamot.bench','io.github.yinvoker.foxlet.bench']))
apks={'v0.3.0':root/'${MEASUREMENT_DIR}/v0.3.0.apk','v0.5.0':root/'benchmark-app/build/outputs/apk/debug/benchmark-app-debug.apk'}
def shell(s):return subprocess.check_output(adb+['shell',s],text=True,timeout=30).strip()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(v):(out/v/'results.json').write_text(json.dumps(reports[v],ensure_ascii=False,indent=2)+'\n')
def sensors():
 fields=dict(re.findall(r'^\s*(level|temperature|status|AC powered|USB powered):\s*(\S+)',shell('dumpsys battery'),re.M))
 return {'battery_temp_c':int(fields['temperature'])/10,'battery_level':int(fields['level']),'battery_status':fields.get('status'),'ac_powered':fields.get('AC powered'),'usb_powered':fields.get('USB powered'),'foreground':bench_device.capture_foreground(shell),'max_freq_khz':{str(c):int(shell(f'cat /sys/devices/system/cpu/cpu{c}/cpufreq/scaling_max_freq')) for c in [2,7]}}
def models(package):return sorted(shell(f'run-as {package} sh -c '+shlex.quote('find files/models no_backup/com.google.mlkit.translate.models -type f -exec sha256sum {} \\;')).splitlines())
def ready(package):
 state=bench_device.capture_foreground(shell)
 return state['wakefulness']=='Awake' and state['lockscreen'] is False and state['focused_package']==package
reports={};corpus={d:sha(root/f'benchmark-app/src/main/assets/bench/{lang}.txt') for d,lang in [('enzh','eng'),('jazh','jpn')]}
for v in versions:
 package=packages[v];folder=out/v;folder.mkdir()
 previous=root/'${MEASUREMENT_DIR}/app-pair-retry-1'/v
 report=json.loads((previous/'results.json').read_text())
 assert report['status']=='failed' and all(r.get('status')=='complete' for r in report['runs'])
 assert models(package)==report['model_files_sha256_before'], 'Model state changed across protocol transition'
 (folder/'source-files.json').write_bytes((previous/'source-files.json').read_bytes())
 retained=[r for r in report['runs'] if r['threads']!=4]
 for r in retained:(folder/r['result_file']).write_bytes((previous/r['result_file']).read_bytes())
 report.update(suite_id='android-app-v2',status='running',runs=retained)
 report.pop('error',None)
 report['protocol']['transition']='User requested discontinuing four-thread retests after round 1 English cells; completed eligible measurements retained unchanged.'
 report['continued_from']={'file':'app-interrupted-v1/'+v+'/results.json','sha256':sha(previous/'results.json'),'retained_run_ids':[r['run_id'] for r in retained]}
 report['collector_files_sha256'].update({'app_pair_v2.py':sha(Path(__file__)),'tools/app-bench/run-v2.py':sha(root/'tools/app-bench/run.py')})
 reports[v]=report;save(v)
assert reports['v0.3.0']['model_files_sha256_before']==reports['v0.5.0']['model_files_sha256_before'],'Model sets differ'
try:
 for ri in range(3):
  cases=collector.suite_scenarios('android-app-v2')
  if ri%2:cases=list(reversed(cases))
  for direction,engine,threads in cases:
   for v in versions if ri%2==0 else list(reversed(versions)):
    if any((r['round'],r['direction'],r['engine'],r['threads'])==(ri+1,direction,engine,None if engine=='mlkit' else threads) for r in reports[v]['runs']):continue
    package=packages[v];run_id=f'measure_{ri+1}_{direction}_{engine}_{threads}_{uuid.uuid4().hex[:10]}'
    r={'round':ri+1,'run_id':run_id,'direction':direction,'engine':engine,'threads':None if engine=='mlkit' else threads};reports[v]['runs'].append(r);save(v)
    for pkg in packages.values():shell('am force-stop '+pkg)
    r['idle_launch_output']=shell(f'am start -W -n {package}/.MainActivity')
    focus_deadline=time.monotonic()+3
    while not ready(package) and time.monotonic()<focus_deadline:time.sleep(.1)
    gate_deadline=time.monotonic()+180
    while True:
     r['before']=sensors();save(v)
     assert ready(package),'Device not awake/unlocked/focused'
     if all(n>=2630400 for n in r['before']['max_freq_khz'].values()):break
     if time.monotonic()>=gate_deadline:raise RuntimeError('frequency gate timeout; not measured')
     time.sleep(5)
    r['launch_output']=shell(f'am start -W --activity-clear-top -n {package}/.MainActivity --es isolated_phase {direction} --es engine {engine} --ei threads {threads} --es run_id {run_id}');save(v)
    deadline=time.monotonic()+720
    while time.monotonic()<deadline:
     time.sleep(2);result=subprocess.run(adb+['exec-out','run-as',package,'cat',f'files/isolated_{run_id}.json'],capture_output=True,timeout=30)
     if result.returncode:continue
     data=collector.decode_snapshot(result.stdout)
     if data is None:continue
     (out/v/f'{run_id}.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
     if data.get('status') in ['complete','failed']:break
    else:raise TimeoutError('app process timeout')
    r['result_file']=f'{run_id}.json';r['after']=sensors();save(v)
    assert ready(package),'foreground lost'
    collector.validate_result(data,run_id,direction,engine,threads,corpus[direction])
    r['output_hashes']=[p['output_sha256'] for p in data['passes']];r['outputs_stable']=len(set(r['output_hashes']))==1;r['status']='complete';save(v)
    print(ri+1,v,direction,engine,threads,'complete',flush=True)
    shell('am force-stop '+package);time.sleep(15)
 for v in versions:reports[v]['model_files_sha256_after']=models(packages[v]);reports[v]['status']='complete';save(v)
except BaseException as error:
 for v in versions:reports[v]['status']='failed';reports[v]['error']=str(error);save(v)
 raise
