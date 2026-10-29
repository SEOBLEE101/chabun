import { readFileSync, mkdirSync, chmodSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createPresetSyncService } from './service.mjs';
import { recoverStalePreferenceLock } from './store.mjs';

export function createLocalPresetSyncService(env=process.env) {
  const taskRoot=fileURLToPath(new URL('../../../',import.meta.url));
  const directory=resolve(taskRoot,'.setup/pitin-preset-sync');
  mkdirSync(directory,{recursive:true,mode:0o700});
  if(process.platform==='win32') {
    execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',`
      $ErrorActionPreference='Stop'
      $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
      $acl=[System.Security.AccessControl.DirectorySecurity]::new()
      $acl.SetOwner($sid)
      $acl.SetAccessRuleProtection($true,$false)
      $rule=[System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
      $acl.AddAccessRule($rule)
      $directory=[System.IO.DirectoryInfo]::new($env:PITIN_PRESET_PRIVATE_DIRECTORY)
      $directory.SetAccessControl($acl)
      foreach($file in $directory.GetFiles()) {
        $fileAcl=[System.Security.AccessControl.FileSecurity]::new()
        $fileAcl.SetOwner($sid)
        $fileAcl.SetAccessRuleProtection($true,$false)
        $fileRule=[System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow')
        $fileAcl.AddAccessRule($fileRule)
        $file.SetAccessControl($fileAcl)
      }
    `],{env:{...process.env,PITIN_PRESET_PRIVATE_DIRECTORY:directory},windowsHide:true,stdio:'pipe'});
  } else chmodSync(directory,0o700);
  const cert=readFileSync(env.PITIN_PRESET_TLS_CERT??resolve(taskRoot,'.setup/pitin-local-tls-20261024/server-cert.pem'));
  const key=readFileSync(env.PITIN_PRESET_TLS_KEY??resolve(taskRoot,'.setup/pitin-local-tls-20261024/server-key.pem'));
  return createPresetSyncService({storeFile:resolve(directory,'preferences.json'),tls:{cert,key,minVersion:'TLSv1.2'}});
}

if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href && process.argv.includes('--recover-lock')) {
  try {
    recoverStalePreferenceLock(fileURLToPath(new URL('../../../.setup/pitin-preset-sync/preferences.json',import.meta.url)));
    console.log('Stale preset writer lock removed after confirming its process is absent. Preferences unchanged.');
  } catch {console.error('Writer lock recovery refused: process live, uncertain, invalid, or no lock. Preferences unchanged.');process.exitCode=1;}
} else if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  let app;
  try {
    const httpPort=Number(process.env.PITIN_PRESET_HTTP_PORT??8792),tlsPort=Number(process.env.PITIN_PRESET_TLS_PORT??9447);
    if(![httpPort,tlsPort].every(p=>Number.isInteger(p)&&p>0&&p<=65535)||httpPort===tlsPort)throw new Error('Invalid ports');
    app=createLocalPresetSyncService();
    for(const [server,port] of [[app.httpServer,httpPort],[app.httpsServer,tlsPort]]) {
      server.on('error',async()=>{console.error('Preset sync listener unavailable. Check ports and private storage.');process.exitCode=1;await app.close();});
      server.listen(port,'127.0.0.1');
    }
    console.log(`Pit-In preset sync: http://127.0.0.1:${httpPort} / HTTPS native port ${tlsPort}. Synthetic preferences only.`);
    for(const signal of ['SIGINT','SIGTERM'])process.once(signal,async()=>{await app.close();});
  } catch {console.error('Preset sync startup refused. Check TLS, private storage integrity, ACL, and writer lock.');process.exitCode=1;if(app)await app.close();}
}
