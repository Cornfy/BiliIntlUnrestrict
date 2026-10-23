const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../biliSendCheck.user.js'), 'utf8');
function context() {
    const storage = new Map();
    let failRecords = false;
    const c = vm.createContext({ Date, console,
        GM_getValue: (k, fallback) => storage.has(k) ? storage.get(k) : fallback,
        GM_setValue: (k, value) => {
            if (failRecords && k === 'test') { failRecords = false; throw new Error('storage full'); }
            storage.set(k, value);
        }
    });
    const pure = source.split('// ==== 纯逻辑区（可独立测试）BEGIN ====')[1].split('// ==== 纯逻辑区（可独立测试）END ====')[0];
    const db = source.slice(source.indexOf('    const DBManager ='), source.indexOf('    // ================= 5.'));
    vm.runInContext("const RECORDS_KEY='test';\n" + pure + db + '\nglobalThis.transfer=CommentTransfer;globalThis.db=DBManager;', c);
    return { c, storage, fail: () => { failRecords = true; } };
}
const casesText = fs.readFileSync(path.join(__dirname, '../../app/src/test/resources/comment-backup-cases.json'), 'utf8');
test('shared merge cases, exact IDs and repeated import', () => {
    const { c } = context();
    for (const scenario of c.parseJsonExact(casesText)) {
        const local = c.transfer.decode(JSON.stringify(scenario.local));
        const incoming = c.transfer.decode(JSON.stringify(scenario.incoming));
        const plan = c.transfer.merge(local, incoming);
        assert.equal(c.normalizeStatus(plan.records[0].status), c.normalizeStatus(scenario.status), scenario.name);
        assert.equal(plan.records[0].timestamp, scenario.time);
        assert.equal(plan.changes.size, scenario.changes);
        assert.equal(plan.kept, scenario.kept);
        assert.equal(JSON.stringify(c.transfer.merge(plan.records, incoming).records), JSON.stringify(plan.records));
        assert.equal(c.transfer.encode(c.transfer.decode(c.transfer.encode(plan.records))), c.transfer.encode(plan.records));
    }
});
test('whole-file validation precedes mutation and identity conflicts preserve undo slot', () => {
    const { c, storage } = context();
    c.db.importBackup('[{"rpid":2,"oid":1,"status":"NORMAL"}]');
    const before = [...storage];
    for (const text of ['[{"rpid":3,"oid":1},{"rpid":4,"oid":null}]', '[{"rpid":2,"oid":99}]', '[{"rpid":2,"oid":1},{"rpid":2,"oid":1}]']) {
        assert.throws(() => c.db.importBackup(text));
        assert.deepEqual([...storage], before);
    }
});
test('single undo slot covers import, local delete and clear; restore consumes it', async () => {
    const { c } = context();
    c.db.importBackup('[{"rpid":2,"oid":1,"status":"NORMAL","timestamp":100}]');
    const original = JSON.stringify(c.db.getAllSync());
    c.db.importBackup('[{"rpid":2,"oid":1,"status":"DELETED","timestamp":50}]');
    c.db.undo();
    assert.equal(JSON.stringify(c.db.getAllSync()), original);
    assert.equal(c.db.canUndo(), false);
    await c.db.delete('2'); assert.equal(c.db.getAllSync().length, 0); c.db.undo();
    assert.equal(JSON.stringify(c.db.getAllSync()), original);
    await c.db.clear(); c.db.undo();
    assert.equal(JSON.stringify(c.db.getAllSync()), original);
});
test('failed storage write preserves both records and previous undo slot', () => {
    const { c, storage, fail } = context();
    c.db.importBackup('[{"rpid":2,"oid":1,"status":"NORMAL"}]');
    const before = [...storage];
    fail();
    assert.throws(() => c.db.importBackup('[{"rpid":2,"oid":1,"status":"DELETED"}]'));
    assert.deepEqual([...storage], before);
});
test('UNKNOWN recheck keeps effective status, timestamp and initial status', async () => {
    const { c } = context();
    await c.db.save({rpid:'2',oid:'1',status:'normal',initial_status:'normal',timestamp:100});
    await c.db.save({rpid:'2',oid:'1',status:'unknown',initial_status:'unknown',timestamp:200});
    const row=c.db.getAllSync()[0];
    assert.equal(row.status, 'normal'); assert.equal(row.timestamp,100); assert.equal(row.initial_status,'normal');
});
test('export keeps legacy numeric IDs without altering numeric content', () => {
    const { c } = context();
    const text=c.transfer.encode(c.transfer.decode('[{"rpid":9223372036854775807,"oid":1000000000000000001,"status":"NORMAL"}]'));
    const rows=c.parseJsonExact(text);
    assert.equal(Array.isArray(rows),true); assert.equal(rows[0].rpid,'9223372036854775807'); assert.equal(rows[0].oid,'1000000000000000001');
    assert.match(text, /"rpid":\s*9223372036854775807/);
    const message='正文包含 "oid":1000000000000000001 和 \n换行 😀';
    const content = c.transfer.encode([{rpid:'2',oid:'1',message}]);
    assert.equal(JSON.parse(content)[0].message, message);
});
