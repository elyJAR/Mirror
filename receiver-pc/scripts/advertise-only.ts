import { Bonjour } from 'bonjour-service'

const bonjour = new Bonjour()
const service = bonjour.publish({
  name: 'Test PC Receiver',
  type: 'mirror-stream',
  protocol: 'tcp',
  port: 8765
})

console.log('Advertising mDNS service: _mirror-stream._tcp.local. on port 8765')
console.log('Press Ctrl+C to stop')

process.on('SIGINT', () => {
  console.log('Stopping advertisement...')
  service.stop(() => {
    bonjour.destroy()
    process.exit()
  })
})
