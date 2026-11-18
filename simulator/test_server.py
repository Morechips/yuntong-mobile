import asyncio, json, time, unittest
from server import SessionGate, TestPeer, message, serve
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

def update(kind='session.start',sid='session-1',seq=1,route=1,phase='active',source='simulation'):
    return message(kind,sessionId=sid,sequence=seq,routeVersion=route,phase=phase,source=source,sentAt=int(time.time()*1000),instruction='前方左转')

class GateTests(unittest.TestCase):
    def test_monotonic(self):
        g=SessionGate();self.assertTrue(g.accept(update())[0]);self.assertFalse(g.accept(update())[0]);self.assertTrue(g.accept(update('route.replace',seq=3,route=2))[0]);self.assertFalse(g.accept(update('navigation.progress',seq=4))[0])
    def test_ended_tombstone(self):
        g=SessionGate();g.accept(update());g.accept(update('session.ended',seq=2,phase='ended'));self.assertFalse(g.accept(update('navigation.snapshot',seq=3))[0]);self.assertTrue(g.accept(update(sid='session-2'))[0]);self.assertFalse(g.accept(update('navigation.snapshot',seq=4))[0])
    def test_source_isolation(self):
        g=SessionGate();g.accept(update());self.assertFalse(g.accept(update('navigation.progress',seq=2,source='amap'))[0])
    def test_pause(self):
        g=SessionGate();g.accept(update());g.accept(update('session.paused',seq=2,phase='paused'));self.assertFalse(g.accept(update('navigation.progress',seq=3))[0]);self.assertTrue(g.accept(update('session.active',seq=4))[0])
    def test_reconnect_snapshot(self):
        g=SessionGate();self.assertTrue(g.accept(update('navigation.snapshot',seq=100,route=8))[0]);self.assertTrue(g.accept(update('navigation.snapshot',seq=101,route=8))[0])
    def test_expired(self):
        a=update();a['sentAt']-=20000;self.assertFalse(SessionGate().accept(a)[0])

class SocketTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.peer=TestPeer('test-token-for-local-only','blocked')
        self.peer.record=lambda data:None
        self.server=await serve(self.peer.handle,'127.0.0.1',0)
        self.url=f'ws://127.0.0.1:{self.server.sockets[0].getsockname()[1]}/link'
    async def asyncTearDown(self):self.server.close();await self.server.wait_closed()
    async def test_auth(self):
        async with connect(self.url) as ws:
            with self.assertRaises(ConnectionClosed):await ws.recv()
    async def test_navigation_and_frame_roundtrip(self):
        async with connect(self.url,additional_headers={'Authorization':'Bearer test-token-for-local-only'}) as ws:
            await ws.send(json.dumps(message('hello')))
            self.assertEqual(json.loads(await ws.recv())['type'],'hello.accepted')
            await ws.send(json.dumps(update()))
            await ws.send(json.dumps(update('navigation.progress',seq=2)))
            got=set()
            async with asyncio.timeout(10):
                while not {'frame','ack','guidance'}.issubset(got):
                    data=json.loads(await ws.recv());got.add(data['type'])
                    if data['type']=='guidance':self.assertIn('转向受阻',data['text']);self.assertEqual(data['sequence'],2)
                    if data['type']=='frame':self.assertTrue(data['jpeg']);self.assertEqual(data['frameId'],data['analysis']['frameId'])
            await ws.send(json.dumps(update('session.ended',seq=3,phase='ended')))
            await ws.send(json.dumps(update('navigation.progress',seq=4)))
            async with asyncio.timeout(5):
                while True:
                    data=json.loads(await ws.recv())
                    if data['type']=='rejected':self.assertEqual(data['reason'],'session_ended');break

if __name__=='__main__':unittest.main(verbosity=2)
