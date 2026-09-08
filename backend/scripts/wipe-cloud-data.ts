import dotenv from 'dotenv';
import path from 'path';
import dns from 'dns';
import { MongoClient } from 'mongodb';

// Ensure standard Google DNS for MongoDB Atlas SRV record resolution
dns.setServers(['8.8.8.8', '1.1.1.1']);

dotenv.config({ path: path.join(__dirname, '../.env') });

const mongoUri = process.env.MONGODB_URI;

async function wipeDatabase() {
  console.log('\n--- WIPING MONGODB DATABASE ---');
  if (!mongoUri) {
    console.error('❌ MONGODB_URI is not defined in .env');
    return;
  }

  const client = new MongoClient(mongoUri);
  try {
    await client.connect();
    console.log('✅ Connected to MongoDB Atlas.');

    const db = client.db();
    const collections = await db.listCollections().toArray();
    console.log(`Found ${collections.length} collections in database "${db.databaseName}":`, collections.map(c => c.name));

    let totalDocs = 0;
    for (const col of collections) {
      if (col.name.startsWith('system.')) continue;
      const result = await db.collection(col.name).deleteMany({});
      console.log(` 🗑️ Cleared collection "${col.name}": deleted ${result.deletedCount} documents.`);
      totalDocs += result.deletedCount;
    }

    console.log(`\n🎉 MongoDB database completely wiped clean! Total documents removed: ${totalDocs}`);
  } catch (err) {
    console.error('❌ Error wiping MongoDB:', err);
  } finally {
    await client.close();
  }
}

wipeDatabase().catch(console.error);
