import fs from 'fs';
import path from 'path';
import { query, pool } from './db';
import { logger } from '../index';

export async function runMigrations(): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Ensure migrations table exists
    await client.query(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        id SERIAL PRIMARY KEY,
        name VARCHAR(255) NOT NULL UNIQUE,
        applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
    `);

    // Look for migration files
    const migrationsDir = path.resolve(__dirname, '../../../infrastructure/database/migrations');
    if (!fs.existsSync(migrationsDir)) {
      throw new Error(`Migrations directory not found at ${migrationsDir}`);
    }

    const files = fs.readdirSync(migrationsDir).filter(f => f.endsWith('.sql')).sort();

    for (const file of files) {
      const { rows } = await client.query('SELECT name FROM schema_migrations WHERE name = $1', [file]);
      if (rows.length === 0) {
        logger.info(`Applying database migration: ${file}`);
        const sql = fs.readFileSync(path.join(migrationsDir, file), 'utf-8');
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations (name) VALUES ($1)', [file]);
        logger.info(`Successfully applied migration: ${file}`);
      } else {
        logger.debug(`Migration ${file} already applied, skipping.`);
      }
    }

    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    logger.error({ error }, 'Migration failed, transaction rolled back.');
    throw error;
  } finally {
    client.release();
  }
}

// Support running directly via CLI (e.g. npx tsx src/database/migrate.ts)
if (require.main === module) {
  runMigrations()
    .then(() => {
      console.log('Migrations completed successfully.');
      process.exit(0);
    })
    .catch((err) => {
      console.error('Migration failed:', err);
      process.exit(1);
    });
}
