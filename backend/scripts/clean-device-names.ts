import dotenv from 'dotenv';
import path from 'path';
import dns from 'dns';
import { MongoClient } from 'mongodb';

dns.setServers(['8.8.8.8', '1.1.1.1']);
dotenv.config({ path: path.join(__dirname, '../.env') });

async function run() {
  const uri = process.env.MONGODB_URI;
  if (!uri) return;
  const client = new MongoClient(uri);
  await client.connect();
  const db = client.db();
  const devices = await db.collection('devices').find({}).toArray();
  for (const d of devices) {
    const cleaned = (d.deviceName || '').replace(/\s*\((Sentry|Controller)\)/gi, '').replace(/\s*-(Sentry|Controller)/gi, '').trim();
    if (cleaned && cleaned !== d.deviceName) {
      await db.collection('devices').updateOne({ _id: d._id }, { $set: { deviceName: cleaned } });
      console.log('Cleaned device in DB:', d.deviceName, '->', cleaned);
    }
  }
  await client.close();
  console.log('Database device names checked and cleaned.');
}

run().catch(console.error);
