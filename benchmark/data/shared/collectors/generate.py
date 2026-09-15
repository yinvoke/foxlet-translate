#!/usr/bin/env python3
"""Derive benchmark displays from archived JSON only. No device/model execution."""
import argparse
import hashlib
import json
import math
from pathlib import Path
from statistics import median

SCENARIOS = [(d,e,t) for d in ('enzh','jazh') for e,t in (('mlkit',None),('bergamot',1),('bergamot',2))]
BEGIN, END = '<!-- BENCHMARK:BEGIN -->', '<!-- BENCHMARK:END -->'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def app_data(directory, score_file):
    report = read(directory/'results.json')
    scores = ({(r['direction'],r['output_sha256']):r['comet_x100'] for r in read(score_file)['scored_outputs']}
              if score_file.exists() else {})
    rows = []
    for direction, engine, threads in SCENARIOS:
        runs = [r for r in report['runs'] if (r['direction'],r['engine'],r['threads']) == (direction,engine,threads) and r.get('status') == 'complete']
        assert len({r['round'] for r in runs}) == len(runs) <= 3, 'Duplicate or excess process rounds'
        values = {k:[] for k in ('cold_ms','warm_ms','cold_pss_mib','warm_pss_mib')}
        quality, hashes, missing_scores = set(), set(), set()
        for run in runs:
            raw = read(directory/run['result_file'])
            assert raw['status']=='complete' and raw['input_count']==200
            assert raw['corpus_sha256']==report['corpus_sha256'][direction]
            passes = raw['passes']
            assert [p['pass'] for p in passes]==[0,1,2]
            for sample in passes:
                assert len(sample['outputs'])==200
                sha = hashlib.sha256(('\n'.join(sample['outputs'])+'\n').encode()).hexdigest()
                assert sha == sample['output_sha256']
                hashes.add(sha)
                if (direction,sha) in scores:
                    quality.add(scores[(direction,sha)])
                else:
                    missing_scores.add(sha)
            values['cold_ms'].append(passes[0]['elapsed_ms'])
            values['warm_ms'].append(median(p['elapsed_ms'] for p in passes[1:]))
            values['cold_pss_mib'].append(passes[0]['metrics']['peakPssMb'])
            values['warm_pss_mib'].append(max(p['metrics']['peakPssMb'] for p in passes[1:]))
        row = {'direction':direction,'engine':engine,'threads':threads,'completed_processes':len(runs),'planned_processes':3,'process_values':values,'output_hashes':sorted(hashes),'comet_x100':[] if missing_scores else sorted(quality),'unscored_output_hashes':sorted(missing_scores)}
        if runs:
            row.update({k:median(v) for k,v in values.items()})
            row['spread_percent'] = {k:100*(max(v)-min(v))/median(v) for k,v in values.items()}
            row['cold_inputs_per_second']=200000/row['cold_ms']
            row['warm_inputs_per_second']=200000/row['warm_ms']
        rows.append(row)
    return {'suite_id':report['suite_id'],'source_status':report['status'],'complete':all(r['completed_processes']==3 for r in rows),'rows':rows}


def native_data(path, version):
    report=read(path)
    rows=[]
    for scenario in report['scenarios']:
        runs=[r for r in report['runs'] if r['version']==version and r['scenario']==scenario and r.get('returncode')==0 and not r.get('error') and len(r.get('samples',[]))==3]
        row={'scenario':scenario,'completed_processes':len(runs)}
        if runs:
            row.update(cold_ms=median(r['samples'][0]['elapsed_ms'] for r in runs),warm_ms=median(median(s['elapsed_ms'] for s in r['samples'][1:]) for r in runs),peak_rss_mib=median(r['samples'][0]['peak_rss_mib'] for r in runs))
        rows.append(row)
    return {'suite_id':report['suite_id'],'version':version,'rows':rows}


def derive(config_file):
    config=read(config_file);base=config_file.parent
    app_dir=(base/config['app']['directory']).resolve();score_file=(base/config['app']['scores']).resolve()
    native_file=(base/config['native']['report']).resolve()
    result={'version':config['version'],'generator_sha256':digest(Path(__file__)),'app':app_data(app_dir,score_file),'native':native_data(native_file,config['native']['version'])}
    inputs=[native_file,score_file,app_dir/'results.json',*[app_dir/r['result_file'] for r in read(app_dir/'results.json')['runs'] if r.get('status')=='complete']]
    if 'baseline' in config:
        spec=config['baseline'];directory=(base/spec['app_directory']).resolve();scores=(base/spec['app_scores']).resolve();native=(base/spec['native_report']).resolve()
        result['baseline']={'version':spec['version'],'app':app_data(directory,scores),'native':native_data(native,spec['version']),'comparison_scope':'Archived historical measurements; not a new paired run. Different dates/conditions and incomplete cells are descriptive references, not regression acceptance.'}
        inputs += [native,scores,directory/'results.json',*[directory/r['result_file'] for r in read(directory/'results.json')['runs'] if r.get('status')=='complete']]
    if 'initial_comparison' in config:
        spec=config['initial_comparison'];path=(base/spec['report']).resolve();old={r['scenario']:r for r in native_data(path,spec['before'])['rows']};new={r['scenario']:r for r in native_data(path,spec['after'])['rows']}
        result['initial_comparison']={'before':spec['before'],'after':spec['after'],'rows':[{'direction':d,'cold_speed_gain_percent':100*(old[a]['cold_ms']/new[b]['cold_ms']-1),'peak_rss_reduction_percent':100*(1-new[b]['peak_rss_mib']/old[a]['peak_rss_mib'])} for d,a,b in [('enzh','enzh_w1','enzh_b512p'),('jazh','pivot_w1','pivot_b512p')]]};inputs.append(path)
    indexed={(r['direction'],r['engine'],r['threads']):r for r in result['app']['rows']};estimates=[]
    for direction in ('enzh','jazh'):
        a,b=[indexed[(direction,'bergamot',n)] for n in (1,2)]
        row={'direction':direction,'kind':'estimate, not measurement'}
        if a['completed_processes'] and b['completed_processes']:
            p=2*(1-b['warm_ms']/a['warm_ms']);row['parallel_fraction']=p
            row['estimated_inputs_per_second']={str(n):200000/(a['warm_ms']*(1-p+p/n)) if 0<=p<=1 else None for n in (4,6)}
        estimates.append(row)
    result['thread_estimates']={'formula':'p=2*(1-T2/T1); Tn=T1*(1-p+p/n); inputs/s=200000/Tn','assumptions':'Constant parallel fraction; no additional scheduling, bandwidth or thermal cost. Not a performance guarantee.','rows':estimates}
    # Paths in derived provenance are relative to the version directory.
    import os
    result['sources']={os.path.relpath(config_file.resolve(),base.resolve()):digest(config_file)}
    result['sources'].update({os.path.relpath(p,base.resolve()):digest(p) for p in inputs if p.exists()})
    return config,result


def render(config,data,en=False):
    report=config['report_link'];version=data['version'];counts=[r['completed_processes'] for r in data['app']['rows']];initial=data.get('initial_comparison')
    lines=[]
    if initial:
        speed=10*math.floor(min(r['cold_speed_gain_percent'] for r in initial['rows'])/10)
        memory=10*math.floor(min(r['peak_rss_reduction_percent'] for r in initial['rows'])/10)
        link=config['initial_comparison']['link']
        lines.append((f'Compared with the initial Android port of Bergamot, [historical Xiaomi 14 measurements]({link}) show roughly **{speed:.0f}% faster single-thread translation and {memory:.0f}% lower peak native memory**; current-version measurements are kept in the [version report]({report}).' if en else f'相对初版 Android 移植的 Bergamot 运行时，[小米 14 历史对照]({link})中单线程翻译速度约提升 **{speed:.0f}%**、原生峰值内存约降低 **{memory:.0f}%**；当前版本实测见[版本报告]({report})。'))
    device=config['device_en' if en else 'device_zh']
    lines += ['','### Comparison with Google ML Kit on-device translation' if en else '### 与 Google ML Kit 端侧翻译对比','',
              '| Item | Configuration |' if en else '| 项目 | 配置 |', '|---|---|',
              f'| {"Version" if en else "测试版本"} | Foxlet {version} |']
    device_parts=device.split(' · ')
    if len(device_parts)==3:
        for label,value in zip(('Device','Processor','Operating system') if en else ('设备','处理器','操作系统'),device_parts):
            lines.append(f'| {label} | {value} |')
    else:
        lines.append(f'| {"Device and environment" if en else "设备与环境"} | {device} |')
    lines += [f'| {"Benchmark protocol" if en else "基准协议"} | `{data["app"]["suite_id"]}` |',
              '| Test configurations | Google ML Kit; Foxlet with 1 / 2 threads |' if en else '| 测试配置 | Google ML Kit；Foxlet 单线程 / 双线程 |',
              '| Dataset | First 200 FLORES-200 devtest inputs per direction |' if en else '| 测试语料 | FLORES-200 devtest，每个语向前 200 条源文 |',
              '| Directions | English → Chinese; Japanese → Chinese |' if en else '| 测试语向 | 英→中、日→中 |',
              '| Build configuration | Debug benchmark APK; Release-optimized native library |' if en else '| 构建配置 | Debug 基准 APK，原生库使用 Release 优化 |']
    if min(counts)<3:
        lines += ['',f'Existing measurements: {min(counts)}–{max(counts)} completed processes per scenario, three passes each. The planned three-process run was not completed; these are reference values, not a passed regression baseline. [Raw data and method]({report}).' if en else f'使用本轮已有数据：每场景完成 {min(counts)}–{max(counts)} 个独立进程、每进程三遍，未补齐原定三轮；以下为参考实测，不代表回归基线验收通过。[原始数据与方法]({report})。']
    else:
        lines += ['',f'Three independent processes × three passes per scenario. [Raw data and method]({report}).' if en else f'每场景三个独立进程 × 三遍。[原始数据与方法]({report})。']
    lines += ['','| Direction / engine / threads | First inputs/s | Warm inputs/s | First PSS MiB | Warm PSS MiB | COMET × 100 |' if en else '| 语向 / 引擎 / 线程 | 首次速度（条/秒） | 热态速度（条/秒） | 首次 PSS MiB | 热态 PSS MiB | COMET × 100 |','|---|---:|---:|---:|---:|---:|']
    for row in data['app']['rows']:
        direction=({'enzh':'English → Chinese','jazh':'Japanese → Chinese'} if en else {'enzh':'英→中','jazh':'日→中'})[row['direction']];engine='ML Kit' if row['engine']=='mlkit' else f"Foxlet / {row['threads']}"
        fields=[]
        for key,noise in [('cold_inputs_per_second','cold_ms'),('warm_inputs_per_second','warm_ms'),('cold_pss_mib','cold_pss_mib'),('warm_pss_mib','warm_pss_mib')]:
            fields.append('—' if key not in row else f"{row[key]:.2f}"+('†' if row['spread_percent'][noise]>10 else ''))
        quality=' / '.join(f'{x:.2f}' for x in row['comet_x100']) or '—'
        lines.append(f'| {direction} / {engine} | '+' | '.join(fields+[quality])+' |')
    lines += ['',('First-pass timing includes engine/model creation. **PSS (Proportional Set Size)** is the physical memory private to a process plus its proportional share of shared memory. **First/warm PSS** are medians of per-process sampled peaks during the first pass / subsequent two passes, including JVM, UI and native memory, sampled every 250 ms. COMET is a quality score, not an accuracy percentage. † Range / median exceeds 10%; incomplete repeats cannot establish stability.' if en else '首次计时包含引擎/模型创建。**PSS（Proportional Set Size，按比例分摊的物理内存）**是进程独占的物理内存，加上按比例分摊的共享内存。**首次 PSS / 热态 PSS**分别取各进程首遍 / 后两遍翻译期间的峰值，再计算中位数；包含 JVM、UI 和原生库，每 250 ms 采样。COMET 是质量评分，不是准确率百分比。† 已测进程的极差 / 中位数超过 10%；轮次不足时不能据此判定稳定性。'),'',('**Two threads are usually recommended**, balancing speed and memory; use one when memory is tight. Further threads generally bring diminishing returns and more memory/scheduling overhead.' if en else '**通常推荐双线程**，兼顾速度和内存；内存紧张时选单线程。继续增加线程通常收益边际递减，并增加内存和调度开销。'),'', '| Direction | 4 threads, estimated | 6 threads, estimated |' if en else '| 语向 | 4 线程预估 | 6 线程预估 |','|---|---:|---:|']
    for row in data['thread_estimates']['rows']:
        label=({'enzh':'English → Chinese','jazh':'Japanese → Chinese'} if en else {'enzh':'英→中','jazh':'日→中'})[row['direction']]
        values=[row.get('estimated_inputs_per_second',{}).get(str(n)) for n in (4,6)]
        lines.append('| '+label+' | '+' | '.join('—' if x is None else f'≈ {x:.0f} '+('inputs/s' if en else '条/秒') for x in values)+' |')
    lines += ['',('These are **rough estimates, not measurements**, extrapolated from 1/2-thread warm times. Additional bandwidth, scheduling and thermal costs are excluded; see the [source calculations]('+config['derived_link']+'). They are not performance guarantees.' if en else '四、六线程为**粗略预估，未经实测**，按单、双线程热态耗时外推，未计入额外带宽竞争、调度和发热成本；[计算依据]('+config['derived_link']+')已公开，不作为性能承诺。')]
    return '\n'.join(lines)+'\n'


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('source_index',type=Path);parser.add_argument('--update-readmes',action='store_true');args=parser.parse_args()
    config,data=derive(args.source_index);output=args.source_index.parent/'derived';output.mkdir(exist_ok=True)
    (output/'summary.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    for en,name in [(False,'README.md'),(True,'README.en.md')]:
        fragment=render(config,data,en);(output/name).write_text(fragment)
        if args.update_readmes:
            path=Path(__file__).resolve().parents[2]/name;text=path.read_text();assert text.count(BEGIN)==text.count(END)==1,'README benchmark markers missing or duplicated'
            a=text.index(BEGIN)+len(BEGIN);b=text.index(END);text=text[:a]+'\n\n'+fragment+'\n'+text[b:]
            memory_begin, memory_end='<!-- BENCHMARK:MEMORY:BEGIN -->','<!-- BENCHMARK:MEMORY:END -->'
            if memory_begin in text:
                memory=['| Scenario | Measured peak PSS reference | Suggested translation memory budget |' if en else '| 场景 | 进程峰值 PSS 参考 | 翻译场景内存预算建议 |','|---|---|---|']
                for direction,threads,budget in [('enzh',1,'512 MiB'),('jazh',1,'1 GiB'),('jazh',2,'2 GiB')]:
                    row=next(r for r in data['app']['rows'] if (r['direction'],r['engine'],r['threads'])==(direction,'bergamot',threads))
                    value=math.ceil(max(row['cold_pss_mib'],row['warm_pss_mib'])) if row['completed_processes'] else None
                    label=({'enzh':'English → Chinese','jazh':'Japanese → English → Chinese'} if en else {'enzh':'英→中','jazh':'日→英→中'})[direction]
                    memory.append(f'| {label} / {threads} | '+('—' if value is None else f'≈ {value} MiB')+f' | ≈ {budget} |')
                a=text.index(memory_begin)+len(memory_begin);b=text.index(memory_end);text=text[:a]+'\n\n'+'\n'.join(memory)+'\n\n'+text[b:]
            path.write_text(text)
    print(f'Derived displays from archived files only: {output}')


if __name__=='__main__':main()
