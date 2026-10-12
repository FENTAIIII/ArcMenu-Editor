"""Verify decoded timeline audio on the isolated Fabric client through its real MCP endpoint.

Arguments: isolated MCP properties, output directory. Requires the selected effect-audio-qa
resource pack and matching server effects/assets/audio-preview-qa.ogg prepared under build.
The script does not print credentials, launch instances, or modify ordinary user projects.
"""
import base64
import json
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


config = Path(sys.argv[1])
output = Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
props = dict(line.split('=', 1) for line in config.read_text(encoding='utf-8').splitlines()
             if '=' in line and not line.startswith('#'))
sequence = 0
checks = []
observations = {}
stage = 'initialize'
project = 'audio-preview-qa-' + str(int(time.time()))
source = 'audio-preview-qa.ogg'


def rpc(method, params=None):
    global sequence
    sequence += 1
    request = urllib.request.Request(
        f"http://127.0.0.1:{props['port']}/mcp",
        json.dumps({'jsonrpc': '2.0', 'id': sequence, 'method': method,
                    'params': params or {}}).encode(),
        headers={'Authorization': 'Bearer ' + props['token'],
                 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
                 'MCP-Protocol-Version': '2025-11-25'})
    response = json.load(urllib.request.urlopen(request, timeout=30))
    assert 'error' not in response, response.get('error')
    return response['result']


def call(name, **arguments):
    result = rpc('tools/call', {'name': name, 'arguments': arguments})
    if result.get('isError'):
        raise RuntimeError(result['content'])
    return result


def data(name, **arguments):
    return call(name, **arguments)['structuredContent']


def state():
    return data('arcmenu_state')


def audio(snapshot):
    assert 'audioPreview' in snapshot, 'The client must expose real audioPreview diagnostics'
    return snapshot['audioPreview']


def voice(snapshot, cue='qa-main'):
    return next((v for v in audio(snapshot).get('voices', []) if v['id'] == cue and v['playing']), None)


def poll(predicate, description, timeout=10, interval=.04):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = state()
        if predicate(last):
            return last
        time.sleep(interval)
    raise AssertionError(f'{description}: {json.dumps(last, ensure_ascii=True)}')


def ready(snapshot):
    return next((a for a in audio(snapshot).get('assets', [])
                 if a['source'] == source and str(a['phase']).lower() == 'ready'), None)


def cue(cue_id='qa-main', frame=0, end=-1, enabled=True, pitch=1):
    return data('effect_cue', id=cue_id, frame=frame, end=end, kind='sound',
                source=source, volume=.7, pitch=pitch, enabled=enabled)


def control(action, **arguments):
    return data('effect_control', action=action, **arguments)


def pause():
    if state()['effect']['playing']:
        control('play')
    return poll(lambda s: not s['effect']['playing'] and audio(s)['voiceCount'] == 0,
                'Pause must release all audio voices')


def assert_offset(snapshot, cue_id='qa-main', expected=None, tolerance=.3):
    current = voice(snapshot, cue_id)
    assert current is not None, f'Expected a playing OpenAL voice for {cue_id}'
    if expected is None:
        event = next(c for c in snapshot['effect']['cues'] if c['id'] == cue_id)
        expected = (snapshot['playhead'] - event['frame']) / snapshot['effect']['fps'] * event['pitch']
    assert abs(current['offsetSeconds'] - expected) <= tolerance, (current, expected)
    return current['offsetSeconds']


try:
    rpc('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                       'clientInfo': {'name': 'Effect audio verification', 'version': '1'}})
    assert state()['connected'], 'Connect the isolated test client first'
    data('arcmenu_open', mode='effect', id=project, fromMenu='example')
    data('effect_composition', fps=20, duration=240, workStart=0, workEnd=240, loop=False)
    for event in state()['effect']['cues']:
        data('effect_cue', id=event['id'], delete=True)
    control('audio-preview', enabled=True)
    cue()

    stage = 'preload'
    s = poll(lambda s: ready(s) is not None, 'The resource-pack OGG must finish decoding', timeout=30)
    asset = ready(s)
    assert 9.95 <= asset['durationSeconds'] <= 10.05, asset
    observations['decodedAsset'] = asset
    checks.append('The valid selected resource-pack OGG is decoded and reports its actual ten-second duration')

    stage = 'scrub audition'
    # The grain begins locally before the server acknowledgement, so observe it while the seek is in flight.
    with ThreadPoolExecutor(max_workers=1) as requests:
        seek = requests.submit(control, 'seek', frame=100)
        s = poll(lambda s: voice(s) is not None, 'A paused seek must audition a short grain', timeout=2, interval=.01)
        seek.result(timeout=30)
    observations['scrubOffsetSeconds'] = assert_offset(s, expected=5, tolerance=.25)
    assert not s['effect']['playing']
    poll(lambda s: audio(s)['voiceCount'] == 0, 'Scrub audio must stop without starting timeline playback', timeout=2)
    checks.append('Seeking directly to frame 100 auditions the five-second file position and automatically releases the grain')

    stage = 'mid-clip playback'
    control('play')
    s = poll(lambda s: voice(s) is not None, 'Mid-clip playback must start an actual voice')
    first_offset = assert_offset(s)
    assert 4.9 <= first_offset <= 5.5, first_offset
    time.sleep(.6)
    s = poll(lambda s: voice(s) is not None, 'The voice must continue across ordinary server snapshots')
    later_offset = assert_offset(s)
    assert later_offset > first_offset + .3, (first_offset, later_offset)
    observations['playbackOffsetsSeconds'] = [first_offset, later_offset]
    checks.append('Playback starts at the selected decoded sample and advances across periodic snapshots')

    stage = 'pause and resume'
    paused = pause()
    paused_frame = paused['playhead']
    time.sleep(.3)
    assert abs(state()['playhead'] - paused_frame) <= .05
    control('play')
    s = poll(lambda s: voice(s) is not None, 'Resume must restore the saved timeline position')
    assert_offset(s)
    assert voice(s)['offsetSeconds'] >= paused_frame / 20 - .2
    pause()
    checks.append('Pause releases OpenAL voices; resume continues at the paused file offset')

    stage = 'overlapping clips'
    cue('qa-overlap', frame=40)
    control('seek', frame=100)
    control('play')
    s = poll(lambda s: voice(s) is not None and voice(s, 'qa-overlap') is not None,
             'Overlapping copies of one decoded clip must use independent voices')
    assert_offset(s)
    assert_offset(s, 'qa-overlap')
    assert abs(voice(s)['offsetSeconds'] - voice(s, 'qa-overlap')['offsetSeconds'] - 2) <= .15
    observations['overlap'] = audio(s)['voices']
    cue('qa-overlap', frame=40, enabled=False)
    poll(lambda s: voice(s, 'qa-overlap') is None, 'A muted cue must release its own voice')
    data('effect_cue', id='qa-overlap', delete=True)
    pause()
    checks.append('Overlapping clips retain independent offsets and a muted cue releases its voice')

    stage = 'natural clip end'
    control('seek', frame=198)
    control('play')
    poll(lambda s: s['playhead'] > 204 and audio(s)['voiceCount'] == 0,
         'The audio must finish naturally while the longer composition keeps playing', timeout=3)
    assert state()['effect']['playing']
    pause()
    checks.append('A natural sound cue ends at the decoded duration rather than the composition duration')

    stage = 'trim and pitch'
    cue(end=120)
    control('seek', frame=118)
    control('play')
    poll(lambda s: s['playhead'] > 124 and audio(s)['voiceCount'] == 0,
         'An explicit clip trim must stop audio at its end frame', timeout=3)
    pause()
    cue(pitch=2)
    control('seek', frame=60)
    control('play')
    s = poll(lambda s: voice(s) is not None, 'A pitched clip must start at the scaled sample offset')
    assert_offset(s)
    assert 5.9 <= voice(s)['offsetSeconds'] <= 6.7
    pause()
    cue()
    checks.append('Explicit trims stop independently of composition playback; pitch scales the selected source offset')

    stage = 'work-area loop'
    data('effect_composition', fps=20, duration=240, workStart=80, workEnd=100, loop=True)
    control('seek', frame=95)
    control('play')
    offsets = []
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        s = state()
        current = voice(s)
        if current is not None:
            offsets.append(current['offsetSeconds'])
        time.sleep(.04)
    assert any(a - b > .4 for a, b in zip(offsets, offsets[1:])), offsets
    assert all(3.8 <= offset <= 5.2 for offset in offsets), offsets
    observations['loopOffsetsSeconds'] = offsets
    checks.append('Work-area loops restart audio at the work-start source position')

    stage = 'preview mute and cleanup'
    control('audio-preview', enabled=False)
    muted = poll(lambda s: not audio(s)['enabled'] and audio(s)['voiceCount'] == 0,
                 'Local preview mute must release every voice')
    assert muted['effect']['playing'], 'Audio mute must preserve visual preview playback'
    control('audio-preview', enabled=True)
    poll(lambda s: voice(s) is not None, 'Unmute must resume audio at the current timeline position')
    pause()
    data('effect_composition', fps=20, duration=240, workStart=0, workEnd=240, loop=False)
    control('seek', frame=100)
    poll(lambda s: audio(s)['voiceCount'] == 0, 'The last scrub grain must release', timeout=2)
    image = next(c for c in call('arcmenu_screenshot', scope='window', maxWidth=1280)['content']
                 if c['type'] == 'image')
    (output / 'audio-preview-waveform.png').write_bytes(base64.b64decode(image['data']))
    control('save')
    data('arcmenu_open', mode='menu', id='example')
    s = poll(lambda s: s.get('effect') is None and audio(s)['voiceCount'] == 0,
             'Closing Effect must release audio and preserve ordinary menu editing')
    assert s['scene']['menuId'] == 'example'
    checks.append('Mute preserves visual playback, unmute seeks correctly, and returning to an ordinary menu releases audio')
    result = {'pass': True, 'project': project, 'checks': checks, 'observations': observations,
              'waveformScreenshot': 'audio-preview-waveform.png', 'audioPreview': audio(s)}
except Exception as error:
    result = {'pass': False, 'project': project, 'stage': stage, 'error': str(error),
              'checks': checks, 'observations': observations}
    try:
        control('audio-preview', enabled=False)
        control('stop')
    except Exception:
        pass
    (output / 'audio-results.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    raise
else:
    (output / 'audio-results.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
