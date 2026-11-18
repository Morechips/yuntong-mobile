"""PC-only WebSocket test peer. No RDK protocol or actuator control is implemented."""
from __future__ import annotations
import argparse, asyncio, base64, contextlib, hashlib, hmac, io, json, secrets, sys, time, uuid
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / '.tools' / 'python'))
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

SCHEMA = 'yuntong-link-test-v1'
def message(kind, **fields): return dict(schema=SCHEMA, type=kind, **fields)

class SessionGate:
    """Keeps source, route and sequence monotonic; ended sessions cannot restart."""
    def __init__(self):
        self.session = None
        self.source = None
        self.route = 0
        self.sequence = 0
        self.phase = 'idle'
        self.ended = set()
        self.current = {}

    def accept(self, data):
        kind = data.get('type')
        if kind not in {'session.start','session.active','session.paused','session.ended','route.replace','navigation.snapshot','navigation.location','navigation.progress','user.action'}:
            return False, 'unknown_type'
        sid, source, route, seq = (data.get(k) for k in ('sessionId','source','routeVersion','sequence'))
        if not isinstance(sid,str) or not sid or len(sid)>100 or source not in {'simulation','amap'} or type(route) is not int or type(seq) is not int or route<1 or seq<1:
            return False, 'invalid_metadata'
        if sid in self.ended: return False, 'session_ended'
        phase=data.get('phase')
        if phase not in {'active','paused','ended'}:return False,'invalid_phase'
        sent=data.get('sentAt')
        if not isinstance(sent,(int,float)) or abs(time.time()*1000-sent)>15000:return False,'expired_or_clock_skew'
        if sid != self.session:
            if kind not in {'session.start','navigation.snapshot'}:return False,'session_not_started'
            if self.session:self.ended.add(self.session)
            self.session,self.source,self.route,self.sequence=sid,source,route,0
        if source!=self.source:return False,'mixed_source'
        if seq<=self.sequence:return False,'duplicate_or_out_of_order'
        if route<self.route:return False,'old_route'
        if route>self.route and kind not in {'route.replace','navigation.snapshot'}:return False,'route_missing'
        if self.phase=='paused' and kind in {'navigation.progress'}:return False,'session_paused'
        self.route,self.sequence,self.phase=route,seq,phase
        self.current=data
        if phase=='ended':self.ended.add(sid)
        return True,'accepted'

def demo_jpeg(index):
    from PIL import Image, ImageDraw, ImageFont
    image=Image.new('RGB',(640,400),'#eef2f5');d=ImageDraw.Draw(image)
    font_path=Path('C:/Windows/Fonts/msyh.ttc')
    font=ImageFont.truetype(str(font_path),28) if font_path.exists() else ImageFont.load_default()
    d.rectangle((0,0,640,65),fill='#3d607b')
    d.text((22,14),'云瞳 · 电脑模拟图像',font=font,fill='white')
    d.polygon([(240,400),(300,100),(350,100),(410,400)],fill='#b5c4cf')
    d.rectangle((445,150,560,295),fill='#8b9faa');d.text((440,310),'示例物体',font=font,fill='#182c3c')
    d.text((20,85),'教学楼入口 150米',font=font,fill='#182c3c')
    d.text((20,345),f'模拟帧 {index} · 非实时道路',font=font,fill='#182c3c')
    stream=io.BytesIO();image.save(stream,'JPEG',quality=78);return stream.getvalue()

class TestPeer:
    def __init__(self, token, scenario='normal', image=None, request_every=0, log=None):
        self.token,self.scenario,self.image,self.request_every=token,scenario,image,request_every
        self.gate=SessionGate();self.log=log;self.connected=False;self.last_frame=None;self.requests={}

    def record(self, data):
        clean={k:v for k,v in data.items() if k not in {'jpeg','Authorization','token'}}
        print(json.dumps(clean,ensure_ascii=False),flush=True)
        if self.log:
            with Path(self.log).open('a',encoding='utf-8') as f:f.write(json.dumps(clean,ensure_ascii=False)+'\n')

    async def handle(self, ws):
        if ws.request.path!='/link' or not hmac.compare_digest(ws.request.headers.get('Authorization',''),f'Bearer {self.token}'):
            await ws.close(1008,'authentication required');return
        if self.connected:await ws.close(1013,'one test phone at a time');return
        self.connected=True
        try:
            hello=json.loads(await asyncio.wait_for(ws.recv(),10))
            if hello.get('schema')!=SCHEMA or hello.get('type')!='hello':await ws.close(1008,'hello required');return
            await ws.send(json.dumps(message('hello.accepted',testOnly=True,capabilities=['navigation.receive','frame.jpeg','guidance.test','recognition.bridge'],clock=int(time.time()*1000))))
            self.requests.clear()
            frames=asyncio.create_task(self.frames(ws))
            try:
                async for raw in ws:
                    data=json.loads(raw)
                    if data.get('schema')!=SCHEMA:continue
                    if data.get('type')=='recognition.result':
                        request=self.requests.pop(data.get('requestId'),None)
                        valid=bool(request and request[0]==data.get('frameId') and time.monotonic()-request[1]<40)
                        self.record(dict(event='recognition.return',accepted=valid,result=data));continue
                    ok,reason=self.gate.accept(data)
                    self.record(dict(event='phone.update',accepted=ok,reason=reason,data=data))
                    if not ok:
                        await ws.send(json.dumps(message('rejected',reason=reason,sessionId=data.get('sessionId'),sequence=data.get('sequence'))));continue
                    metadata={key:data[key] for key in ('sessionId','source','routeVersion','sequence')}
                    ack=message('ack',**metadata,stage='received')
                    await ws.send(json.dumps(ack))
                    if self.scenario=='duplicate':await ws.send(json.dumps(ack))
                    if data['type']=='user.action':
                        await ws.send(json.dumps(message('ack',**metadata,stage='executed',testOnly=True)))
                    if data['phase']=='active' and data['type'] in {'navigation.progress','navigation.snapshot'}:
                        blocked=self.scenario=='blocked' and ('左' in data.get('instruction','') or '转' in data.get('instruction',''))
                        guidance='模拟设备：转向受阻，请停下并确认周围环境。' if blocked else '模拟设备已收到导航信息：'+data.get('instruction','等待地图提示')
                        result=message('guidance',**metadata,text=guidance,pauseReason='turn_blocked' if blocked else None,testOnly=True)
                        if self.scenario=='late':await asyncio.sleep(6)
                        await ws.send(json.dumps(result,ensure_ascii=False))
                        if self.scenario=='duplicate':await ws.send(json.dumps(result,ensure_ascii=False))
            finally:
                frames.cancel()
                with contextlib.suppress(asyncio.CancelledError,ConnectionClosed):await frames
        except (ConnectionClosed,asyncio.TimeoutError,json.JSONDecodeError):pass
        finally:self.connected=False

    async def frames(self, ws):
        index=0
        while True:
            index+=1
            jpeg=self.image.read_bytes() if self.image else await asyncio.to_thread(demo_jpeg,index)
            if len(jpeg)>1_500_000:raise ValueError('test JPEG must be less than 1.5 MB')
            frame_id=str(uuid.uuid4());self.last_frame=frame_id
            analysis={'source':'simulation','frameId':frame_id,'text':'示例分析图层，未运行感知模型','boxes':[{'label':'示例物体','left':.695,'top':.375,'right':.875,'bottom':.738}]}
            await ws.send(json.dumps(message('frame',frameId=frame_id,source='simulation',jpeg=base64.b64encode(jpeg).decode(),analysis=analysis)))
            await ws.send(json.dumps(message('device.status',deviceSequence=index,status='电脑模拟设备在线',perception='模拟感知结果，非眼镜实测')))
            if self.request_every and index%self.request_every==0:
                request_id=str(uuid.uuid4());self.requests[request_id]=(frame_id,time.monotonic())
                await ws.send(json.dumps(message('recognition.request',requestId=request_id,frameId=frame_id,question='请读出画面中的文字')))
            await asyncio.sleep(1)

async def main(args):
    token_file=Path(args.token_file)
    if not token_file.exists():token_file.write_text(secrets.token_urlsafe(24),encoding='utf-8')
    token=token_file.read_text(encoding='utf-8').strip()
    if not 16<=len(token)<=128:raise ValueError('token length must be 16..128')
    peer=TestPeer(token,args.scenario,Path(args.image) if args.image else None,args.request_every,args.log)
    print(f'Test peer listening on {args.host}:{args.port}/link; token is in {token_file.resolve()}. No physical device is controlled.',flush=True)
    async with serve(peer.handle,args.host,args.port,max_size=2_500_000,ping_interval=15,compression=None):await asyncio.Future()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',default='127.0.0.1');parser.add_argument('--port',type=int,default=8766)
    parser.add_argument('--token-file',default='simulator-token.txt');parser.add_argument('--log',default='simulator-events.jsonl')
    parser.add_argument('--scenario',choices=['normal','blocked','duplicate','late'],default='normal')
    parser.add_argument('--image');parser.add_argument('--request-every',type=int,default=0)
    try:asyncio.run(main(parser.parse_args()))
    except KeyboardInterrupt:pass
