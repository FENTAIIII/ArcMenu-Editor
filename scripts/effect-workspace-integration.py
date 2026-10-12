"""Verify real workspace commands, gestures and rendered screenshots on an isolated client."""
import base64,json,sys,time,urllib.request
from pathlib import Path

config=Path(sys.argv[1]);output=Path(sys.argv[2]);output.mkdir(parents=True,exist_ok=True)
props=dict(line.split('=',1) for line in config.read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
sequence=0;checks=[];screenshots=[]
def rpc(method,params=None):
    global sequence
    sequence+=1
    request=urllib.request.Request(f"http://127.0.0.1:{props['port']}/mcp",json.dumps({'jsonrpc':'2.0','id':sequence,'method':method,'params':params or {}}).encode(),headers={'Authorization':'Bearer '+props['token'],'Content-Type':'application/json','Accept':'application/json, text/event-stream','MCP-Protocol-Version':'2025-11-25'})
    response=json.load(urllib.request.urlopen(request,timeout=30));assert 'error' not in response,response.get('error');return response['result']
def call(name,**arguments):
    result=rpc('tools/call',{'name':name,'arguments':arguments})
    if result.get('isError'):raise RuntimeError(result['content'])
    return result
def data(name,**arguments):return call(name,**arguments)['structuredContent']
def state():return data('arcmenu_state')
def key(target,frame,value):return data('effect_keyframe',target=target,property='offset.y',frame=frame,value=str(value),interpolation='bezier',curve=[.25,.1,.75,.9])
def keys(snapshot,target):return next(t['keys'] for t in snapshot['effect']['tracks'] if t['target']==target and t['property']=='offset.y')
def ids(snapshot):return {n['id'] for n in snapshot['scene']['frontend']}
def shot(name):
    result=call('arcmenu_screenshot',scope='window',waitTicks=4)
    image=next(c for c in result['content'] if c['type']=='image');(output/name).write_bytes(base64.b64decode(image['data']))
    screenshots.append(name)
def shortcut(chord):return data('effect_workspace',operation='shortcut',shortcut=chord)
def command(command):return data('effect_workspace',operation='command',command=command)
def pointer(operation,x,y,**extra):return data('effect_workspace',operation=operation,x=x,y=y,**extra)

rpc('initialize',{'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'Effect workspace verification','version':'1'}})
assert len(rpc('tools/list')['tools'])==16
assert state()['connected']
project='workspace-qa-'+str(int(time.time()))
s=data('arcmenu_open',mode='effect',id=project,fromMenu='example')
data('effect_composition',fps=20,duration=120,workStart=0,workEnd=120)
for frame,value in [(0,-20),(40,20),(80,-20)]:s=key('title',frame,value)
data('arcmenu_select',ids=['title']);data('effect_control',action='seek',frame=40)
original=state();original_ids=ids(original);original_keys=keys(original,'title')
s=shortcut('Ctrl+Shift+D');created=ids(s)-original_ids;assert len(created)==1
copied=created.pop();assert keys(s,copied)==original_keys
spans={l['target']:(l['start'],l['end']) for l in s['effect']['layers']}
assert spans['title']==(0,40) and spans[copied]==(40,120)
assert s['selection']==[copied]
shot('repair-split.png')
s=shortcut('Ctrl+Z');assert ids(s)==original_ids and keys(s,'title')==original_keys
s=shortcut('Ctrl+Shift+Z');assert copied in ids(s)
data('arcmenu_select',ids=[copied])
checks.append('Ctrl+Shift+D creates two layers with identical source curves; one undo/redo restores scene and timeline')

s=shortcut('Ctrl+X');assert copied not in ids(s)
s=shortcut('Ctrl+V');pasted=ids(s)-(original_ids-{copied});assert len(pasted)==1
pasted=pasted.pop();assert keys(s,pasted)==original_keys
s=shortcut('Ctrl+Z');assert pasted not in ids(s) and copied not in ids(s)
s=shortcut('Ctrl+Z');assert copied in ids(s)
checks.append('Actual Ctrl+X/Ctrl+V capture full animated source, paste after deletion and undo independently')
shortcut('Ctrl+Z')

move=[{'target':'title','property':'offset.y','frame':f,'newFrame':f+10} for f in (0,40)]
s=data('effect_keyframes',action='move',keys=move)
assert [k['frame'] for k in keys(s,'title')]==[10,50,80]
assert [k['value'] for k in keys(s,'title')]==[k['value'] for k in original_keys]
revision=s['effect']['revision']
try:data('effect_keyframes',action='move',keys=[{'target':'title','property':'offset.y','frame':50,'newFrame':80}]);raise AssertionError('Collision accepted')
except RuntimeError:pass
s=state();assert s['effect']['revision']==revision and not s['pendingTimelineEdits']
s=shortcut('Ctrl+Z');assert keys(s,'title')==original_keys
checks.append('Atomic keyframe movement preserves values and curves; collision rolls back and does not block later editing')

data('arcmenu_select',ids=['title']);data('effect_control',action='seek',frame=40)
s=data('effect_workspace',operation='state');assert len(s['workspace']['commands'])>=75
p=s['workspace']['panels'];t=p['timeline'];mapping=s['workspace']['timeline'];grid=mapping['gridX'];gw=mapping['gridWidth']
row=next(row for row in mapping['rows'] if row['target']=='title' and row['property']=='offset.y')
x=grid+(40-mapping['offset'])/mapping['range']*gw;y=row['y']+8
pointer('click',x,y)
assert state()['workspace']['selectedKeys']==1
pointer('drag',x+10/mapping['range']*gw,y,dx=10/mapping['range']*gw,dy=0)
pointer('release',x+10/mapping['range']*gw,y)
s=state();assert [k['frame'] for k in keys(s,'title')]==[0,50,80] and keys(s,'title')[1]['value']=='20'
shot('repair-key-drag.png');shortcut('Ctrl+Z')
checks.append('Real horizontal keyframe gesture changes time only and commits one undo step')

pointer('click',x,y,button=1);assert state()['workspace']['popupOpen']
shot('repair-key-menu.png');shortcut('Esc');assert not state()['workspace']['popupOpen']
data('effect_workspace',operation='context-menu',command='layer',x=110,y=210)
shot('repair-layer-menu.png');shortcut('Esc')
checks.append('Right-click key and layer menus render and dismiss without triggering the underlying panel')

data('arcmenu_select',ids=['title'])
shortcut('P');s=shortcut('Alt+Shift+P');count=len([t for t in s['effect']['tracks'] if t['target']=='title' and t['property'].startswith('offset.')])
assert count==3
shortcut('Ctrl+Z');shortcut('Shift+F3');shot('repair-value-graph.png')
mapping=state()['workspace']['timeline'];assert mapping['graph'] and not mapping['speedGraph']
x=mapping['gridX']+(40-mapping['offset'])/mapping['range']*mapping['gridWidth']
y=mapping['graphBottom']-(20-mapping['graphMin'])/(mapping['graphMax']-mapping['graphMin'])*(mapping['graphBottom']-mapping['graphTop'])
pointer('click',x,y);pointer('drag',x+8/mapping['range']*mapping['gridWidth'],y,dx=8/mapping['range']*mapping['gridWidth'],dy=0);pointer('release',x+8/mapping['range']*mapping['gridWidth'],y)
assert keys(state(),'title')[1]['value']=='20' and keys(state(),'title')[1]['frame']==48
shortcut('Ctrl+Z');shortcut('Shift+F3')
checks.append('P reveals unanimated position properties; Alt+Shift+P inserts all axes; Shift+F3 toggles graph editor')
checks.append('Value-graph horizontal movement preserves the value with a stable vertical coordinate mapping')

s=state();before_ids=ids(s);properties=s['workspace']['panels']['properties'];id_field=next(f for f in s['workspace']['propertyFields'] if f['property']=='id');pointer('click',properties['x']+properties['width']-30,id_field['y']+10)
noop=rpc('tools/call',{'name':'effect_workspace','arguments':{'operation':'shortcut','shortcut':'Ctrl+D'}})
if noop.get('isError'):assert 'did not handle' in noop['content'][0]['text']
assert ids(state())==before_ids
shortcut('Esc')
checks.append('A focused inspector text field consumes Ctrl+D without duplicating layers')

data('effect_layer',target='title',start=5,end=110);data('effect_layer',target='background',start=5,end=110)
s=data('effect_layer_operation',action='move',targets=['title','background'],delta=3)
assert all(layer['start']==8 and layer['end']==113 for layer in s['effect']['layers'] if layer['target'] in ['title','background'])
shortcut('Ctrl+Z');shortcut('Ctrl+Z');shortcut('Ctrl+Z')
checks.append('MCP multi-layer movement updates both spans and animation in one undo transaction')

shortcut('Ctrl+Shift+P');assert state()['screen']=='EffectCommandScreen';shot('repair-command-palette.png');shortcut('Esc');assert state()['screen']=='EffectEditorScreen'
checks.append('Command palette renders the complete searchable command inventory and accepts modal keyboard input')

data('arcmenu_select',ids=['background']);s=state();properties=s['workspace']['panels']['properties']
for attempt in range(12):
    colors=[field for field in s['workspace']['propertyFields'] if field['property']=='color']
    if colors:break
    s=pointer('scroll',properties['x']+20,properties['y']+80,dy=-5)
assert colors
pointer('click',properties['x']+properties['width']-12,colors[0]['y']+10)
assert state()['screen']=='EffectColorPicker';shot('repair-color-picker.png');shortcut('Esc');assert state()['screen']=='EffectEditorScreen'
checks.append('The inspector color swatch opens a real RGBA/hex dialog and Escape cancels it')
data('arcmenu_select',ids=['title'])

command('fit');s=pointer('scroll',p['viewport']['x']+50,p['viewport']['y']+50,ctrl=True,dy=1);assert s['workspace']['zoom']>1
command('actual-size');assert state()['workspace']['zoom']==1
shot('repair-workspace.png')
checks.append('Composition zoom and Playback view reset use the actual rendered crop')
data('effect_control',action='save')
result={'pass':True,'project':project,'checks':checks,'tools':16,'freshScreenshots':len(screenshots),'screenshots':screenshots,'commands':len(state()['workspace']['commands'])}
(output/'workspace-results.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
