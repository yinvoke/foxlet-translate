from pathlib import Path,PurePosixPath
import subprocess,json,hashlib,shlex,time
root=Path.cwd();p=root/'${MEASUREMENT_DIR}';out=p/'native-quality';out.mkdir(exist_ok=False)
adb=['/opt/homebrew/share/android-commandlinetools/platform-tools/adb','-s','${DEVICE_SERIAL}'];report=json.loads((p/'native-retry-1.json').read_text());remote=str(PurePosixPath(shlex.split(report['runs'][0]['command'])[2]).parent)
def fnv(outputs):
 h=14695981039346656037
 for text in outputs:
  for c in (text+'\n').encode():h=((h^c)*1099511628211)&((1<<64)-1)
 return format(h,'x')
metadata=json.loads((p/'reference-build.json').read_text());rows=[]
for v in ['v0.3.0','v0.5.0']:
 binary=Path('/tmp/foxlet-050-standard-20260915')/v/'build/quality_reference'
 assert hashlib.sha256(binary.read_bytes()).hexdigest()==next(r['binary_sha256'] for r in metadata if r['version']==v)
 target=remote+'/quality-'+v;subprocess.run(adb+['push',str(binary),target],stdout=subprocess.DEVNULL,check=True);subprocess.run(adb+['shell','chmod','755',target],check=True)
 for direction,scenario,corpus,configs in [('enzh','enzh_b512p','eng',['enzh512p']),('jazh','pivot_b512p','jpn',['jaen512','enzh512p'])]:
  expected={s['output_hash'] for r in report['runs'] if r['version']==v and r['scenario']==scenario for s in r['samples']}
  for repeat in range(1,4):
   command=shlex.join([target,'0']+[remote+'/'+name+'.yml' for name in configs])+' < '+shlex.quote(remote+'/'+corpus+'.txt')
   result=subprocess.run(adb+['shell',command],capture_output=True,check=True,timeout=180)
   texts=json.loads(result.stdout);assert len(texts)==200 and all(isinstance(t,str) and t for t in texts)
   digest=fnv(texts);assert digest in expected,(v,direction,digest,expected)
   file=out/f'{v}-{direction}-r{repeat}.json';file.write_text(json.dumps(texts,ensure_ascii=False,indent=2)+'\n')
   rows.append({'version':v,'direction':direction,'repeat':repeat,'file':file.name,'file_sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'output_sha256':hashlib.sha256(('\n'.join(texts)+'\n').encode()).hexdigest(),'native_fnv1a64':digest,'matches_timed_output':True,'command':command})
   (out/'manifest.json').write_text(json.dumps({'protocol':'quality-only, fixed native b512p configuration; three processes per version/direction','native_timing_report_sha256':hashlib.sha256((p/'native-retry-1.json').read_bytes()).hexdigest(),'builds':metadata,'runs':rows},indent=2)+'\n')
   print(v,direction,repeat,'native quality output matches timing hash',flush=True)
