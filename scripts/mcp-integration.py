"""Run against an isolated Minecraft client connected to the ArcMenu Effect test server.
Pass its arcmenu-editor-mcp.properties and an output directory. Does not print tokens.
"""
import base64
import json
import sys
import time
import urllib.request
from pathlib import Path

config = Path(sys.argv[1])
output = Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
props = dict(line.split('=', 1) for line in config.read_text().splitlines() if '=' in line and not line.startswith('#'))
request_id = 0


def rpc(method, params=None):
    global request_id
    request_id += 1
    message = {'jsonrpc': '2.0', 'id': request_id, 'method': method, 'params': params or {}}
    request = urllib.request.Request(f"http://127.0.0.1:{props['port']}/mcp", json.dumps(message).encode(), headers={
        'Authorization': 'Bearer ' + props['token'], 'Content-Type': 'application/json',
        'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-11-25'})
    result = json.load(urllib.request.urlopen(request, timeout=180))
    if 'error' in result:
        raise RuntimeError(result['error'])
    return result['result']


def call(name, **args):
    result = rpc('tools/call', {'name': name, 'arguments': args})
    if result.get('isError'):
        raise RuntimeError(result['content'])
    return result


def data(name, **args):
    return call(name, **args)['structuredContent']


init = rpc('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {}, 'clientInfo': {'name': 'ArcMenu integration', 'version': '1'}})
assert init['protocolVersion'] == '2025-11-25'
assert len(rpc('tools/list')['tools']) == 16
assert data('arcmenu_state')['connected'], 'Connect the test client first'
project='mcp-effect-'+str(int(time.time()))
state = data('arcmenu_open', mode='effect', id=project, fromMenu='example')
assert state['effect']['id'] == project
roots=[node['id'] for node in state['scene']['frontend'] if not node['parentId']]
if roots:data('arcmenu_edit',action='delete-many',nodeIds=roots)
for target,kind,initial in [('background','rectangle',{'width':220,'height':80,'color':'#17364F'}),('title','text',{'content':'MCP Effect verification','size':18,'offset':{'z':2}}),('subtitle','text',{'content':'Created through MCP','size':8,'offset':{'y':-20,'z':2}})]:
    state=data('arcmenu_edit',action='create',kind=kind,initialSource=json.dumps({'type':kind,**initial}))
    created=state['selection'][-1]
    if created!=target:data('arcmenu_edit',action='property',target=created,property='id',value=target)
state = data('effect_keyframe', target='title', property='offset.y', frame=0, value='10')
state = data('effect_keyframe', target='title', property='offset.y', frame=20, value='50', interpolation='ease-in-out')
state = data('effect_control', action='seek', frame=10)
title = next(node for node in state['scene']['frontend'] if node['id'] == 'title')
assert float(next(prop['value'] for prop in title['properties'] if prop['key'] == 'offset.y')) == 30
subtitle = next(node for node in state['scene']['frontend'] if node['id'] == 'subtitle')
old_content = next(prop['value'] for prop in subtitle['properties'] if prop['key'] == 'content')
content = 'Verified through MCP' if old_content == 'Created through MCP' else 'Created through MCP'
state = data('arcmenu_edit', action='property', target='subtitle', property='content', value=content)
state = data('arcmenu_select', ids=['title'])
assert state['selection'] == ['title']
state = data('effect_marker', frame=10, label='MCP verification')
state = data('effect_cue', id='mcp-sound', frame=12, kind='sound', source='ENTITY_PLAYER_LEVELUP', volume=0.5)
state = data('effect_cue', id='mcp-action', frame=14, kind='action', source='tell: MCP_RUNTIME_ONLY')
state = data('effect_layer', target='subtitle', start=0, end=90)
title_tracks = len([track for track in state['effect']['tracks'] if track['target'] == 'title'])
state = data('arcmenu_edit', action='delete', target='title')
assert not any(track['target'] == 'title' for track in state['effect']['tracks'])
state = data('arcmenu_edit', action='undo')
assert len([track for track in state['effect']['tracks'] if track['target'] == 'title']) == title_tracks
original_layer = next((layer for layer in state['effect']['layers'] if layer['target'] == 'title'), None)
state = data('effect_layer', target='title', start=3, end=87, enabled=False, solo=True)
source_tracks = [track for track in state['effect']['tracks'] if track['target'] == 'title']
source_layer = next(layer for layer in state['effect']['layers'] if layer['target'] == 'title')
original_ids = {node['id'] for node in state['scene']['frontend']}
state = data('arcmenu_edit', action='duplicate', nodeIds=['title'], preserveParents=True)
copied_ids = {node['id'] for node in state['scene']['frontend']} - original_ids
assert len(copied_ids) == 1, 'Duplicate must expose exactly one new title element'
copied_id = copied_ids.pop()
expected_tracks = [{**track, 'target': copied_id} for track in source_tracks]
expected_layer = {**source_layer, 'target': copied_id}
assert [track for track in state['effect']['tracks'] if track['target'] == copied_id] == expected_tracks
assert next(layer for layer in state['effect']['layers'] if layer['target'] == copied_id) == expected_layer
state = data('arcmenu_edit', action='undo')
assert {node['id'] for node in state['scene']['frontend']} == original_ids
assert not any(track['target'] == copied_id for track in state['effect']['tracks'])
assert not any(layer['target'] == copied_id for layer in state['effect']['layers'])
assert [track for track in state['effect']['tracks'] if track['target'] == 'title'] == source_tracks
state = data('arcmenu_edit', action='redo')
assert {node['id'] for node in state['scene']['frontend']} == original_ids | {copied_id}
assert [track for track in state['effect']['tracks'] if track['target'] == copied_id] == expected_tracks
assert next(layer for layer in state['effect']['layers'] if layer['target'] == copied_id) == expected_layer
state = data('arcmenu_edit', action='undo')
assert {node['id'] for node in state['scene']['frontend']} == original_ids
assert not any(track['target'] == copied_id for track in state['effect']['tracks'])
assert not any(layer['target'] == copied_id for layer in state['effect']['layers'])
restore_layer = original_layer or {'start': 0, 'end': state['effect']['duration'], 'enabled': True, 'solo': False}
state = data('effect_layer', target='title', start=restore_layer['start'], end=restore_layer['end'], enabled=restore_layer['enabled'], solo=restore_layer['solo'])
state = data('arcmenu_select', ids=['title'])
state = data('effect_control', action='save')
assert not state['effect']['dirty']
conflict = rpc('tools/call', {'name': 'effect_marker', 'arguments': {'frame': 12, 'label': 'must reject', 'expectedRevision': -1}})
assert conflict.get('isError')
captures = call('effect_capture_frames', frames=[0, 10, 25, 85], scope='viewport', maxWidth=960, waitTicks=6)
images = [entry for entry in captures['content'] if entry['type'] == 'image']
assert len(images) == 4
for frame, image in zip([0, 10, 25, 85], images):
    png = base64.b64decode(image['data'])
    assert png.startswith(b'\x89PNG\r\n\x1a\n')
    (output / f'mcp-frame-{frame}.png').write_bytes(png)
shot = call('arcmenu_screenshot', scope='window', maxWidth=1280)
(output / 'mcp-editor.png').write_bytes(base64.b64decode(next(c['data'] for c in shot['content'] if c['type'] == 'image')))
state = data('arcmenu_open', mode='menu', id='example')
assert state['scene']['menuId'] == 'example' and state.get('effect') is None
state = data('arcmenu_select', ids=[], tab='backend')
assert state['tab'] == 'backend'
data('arcmenu_select', ids=[], tab='frontend')
import time
new_id = 'mcp-smoke-' + str(int(time.time()))
state = data('arcmenu_open', mode='menu', id=new_id, create=True)
assert state['scene']['menuId'] == new_id and not state['scene']['frontend']
state = data('arcmenu_edit', action='create', kind='text', initialSource='type: text\ncontent: Hello from MCP\nsize: 16\n')
assert len(state['scene']['frontend']) == 1
created = state['scene']['frontend'][0]
assert next(prop['value'] for prop in created['properties'] if prop['key'] == 'content') == 'Hello from MCP'
assert float(next(prop['value'] for prop in created['properties'] if prop['key'] == 'size')) == 16
data('arcmenu_edit', action='save')
summary = {'pass': True, 'toolCount': 16, 'serverConfirmedEdits': True, 'interpolatedY': 30,
           'saved': True, 'revisionConflictRejected': True, 'freshPngFrames': 4, 'menuAndEffect': True,
           'sceneUndoRestoresTracks': True, 'animatedDuplicateUndoRedo': True, 'newMenuCreated': True}
(output / 'mcp-results.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary, indent=2))
