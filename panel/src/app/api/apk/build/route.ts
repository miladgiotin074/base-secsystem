import { NextResponse } from 'next/server';

import { isAccess, requirePanelOpen, type PanelAccess } from '@/server/auth/access';
import { buildAndSendApk } from '@/server/telegram/apkSigner/buildForPort';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
export const maxDuration = 300;

function canBuildPort(access: PanelAccess, portCode: string): boolean {
  if (access.localDev || access.role === 'admin') return true;
  if (access.role === 'manager') {
    return access.ports.includes(portCode);
  }
  return false;
}

export async function POST(request: Request) {
  console.info('apk build POST start');
  const access = await requirePanelOpen(request);
  if (!isAccess(access)) return access;

  let portCode = '';
  let label = '';
  let iconPng: Buffer | undefined;
  try {
    const contentType = request.headers.get('content-type') || '';
    if (contentType.includes('multipart/form-data')) {
      const form = await request.formData();
      portCode = String(form.get('port') || '').trim();
      label = String(form.get('label') || '').trim();
      const icon = form.get('icon');
      if (icon instanceof File && icon.size > 0) {
        iconPng = Buffer.from(await icon.arrayBuffer());
      }
    } else {
      const body = (await request.json()) as {
        port?: string;
        label?: string;
        iconBase64?: string;
      };
      portCode = (body.port || '').trim();
      label = (body.label || '').trim();
      const raw = (body.iconBase64 || '').replace(/^data:[^;]+;base64,/, '');
      if (raw) iconPng = Buffer.from(raw, 'base64');
    }
  } catch {
    return NextResponse.json({ error: 'invalid body' }, { status: 400 });
  }

  if (!/^[A-Za-z0-9]{20}$/.test(portCode)) {
    return NextResponse.json({ error: 'invalid port' }, { status: 400 });
  }
  if (!canBuildPort(access, portCode)) {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }
  if (!label || label.length > 40) {
    return NextResponse.json({ error: 'invalid label' }, { status: 400 });
  }
  if (iconPng && iconPng.length > 4 * 1024 * 1024) {
    return NextResponse.json({ error: 'icon too large' }, { status: 400 });
  }

  try {
    const result = await buildAndSendApk({
      portCode,
      label,
      iconPng,
    });
    return NextResponse.json({ ok: true, fileName: result.fileName, groupId: result.groupId });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('apk build failed', err);
    return NextResponse.json({ error: msg.slice(0, 240) }, { status: 500 });
  }
}
