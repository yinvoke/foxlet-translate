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
out=root/'${MEASUREMENT_DIR}/app-pair-retry-1';out.mkdir(exist_ok=False)
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
 # The harness prefers external models when present; fingerprint the selected internal set only after excluding that override.
 assert shell(f'if [ -d /sdcard/Android/data/{package}/files/models/enzh ]; then echo PRESENT; else echo ABSENT; fi') == 'ABSENT', 'External model override exists; verify the actual model selection before measuring'
 installed=shell('pm path '+package).removeprefix('package:');assert shell('sha256sum '+shlex.quote(installed)).split()[0]==sha(apks[v])
 if v=='v0.3.0':
  source=json.loads((root/'benchmarks/v0.3.0/mi14/performance/app-measure/source-files.json').read_text());head='bfc6a08c0aeaa8a679170d517c5c041a83c01834'
 else:
  snapshot=Path('/tmp/foxlet-050-standard-20260915/v0.5.0');assert snapshot.exists()
  source={str(f.relative_to(snapshot)):sha(f) for d in ['engine','native','jni','foxlet/src','benchmark-app/src'] for f in (snapshot/d).rglob('*') if f.is_file()}
  for name in ['CMakeLists.txt','gradle.properties','build.gradle.kts','settings.gradle.kts','foxlet/build.gradle.kts','benchmark-app/build.gradle.kts','registry.json']:
   source[name]=sha(snapshot/name)
  head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
 (folder/'source-files.json').write_text(json.dumps(source,indent=2)+'\n')
 reports[v]={'suite_id':'android-app-v1','mode':'measure','measured_at':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'status':'running','version':v,'package':package,'apk_sha256':sha(apks[v]),'source_commit':head,'source_kind':'archived-source-with-exact-historical-apk' if v=='v0.3.0' else 'working-tree','source_files_sha256':sha(folder/'source-files.json'),'corpus_sha256':corpus,'device':{'model':shell('getprop ro.product.model'),'fingerprint':shell('getprop ro.build.fingerprint'),'id_sha256':hashlib.sha256(adb[2].encode()).hexdigest()},'mlkit_dependency':'com.google.mlkit:translate:17.0.3','collector_files_sha256':{str(Path(__file__).name):sha(Path(__file__)),'tools/app-bench/run.py':sha(root/'tools/app-bench/run.py'),'tools/bench_device.py':sha(root/'tools/bench_device.py')},'protocol':{'rounds':3,'passes':3,'order':'forward/reverse/forward','cooldown_seconds':15,'gate_khz':2630400,'affinity':'Android foreground scheduling, no taskset','foreground_checks':bench_device.FOREGROUND_CHECKS,'foreground_package':package,'memory':'absolute app PSS; 250 ms samples, not native RSS','performance_eligible':True,'paired_version_order':['AB','BA','AB']},'model_files_sha256_before':models(package),'runs':[]};save(v)
assert reports['v0.3.0']['model_files_sha256_before']==reports['v0.5.0']['model_files_sha256_before'],'Model sets differ'
try:
 for ri in range(3):
  cases=collector.SCENARIOS if ri%2==0 else list(reversed(collector.SCENARIOS))
  for direction,engine,threads in cases:
   for v in versions if ri%2==0 else list(reversed(versions)):
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
