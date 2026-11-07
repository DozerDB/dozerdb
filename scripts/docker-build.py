#!/usr/bin/env python3
"""Build the DozerDB image from the assembled archive using a minimal context.

By default the image is loaded into the local Docker engine under the two local
tags of the line manifest (release-line.toml): dozerdb:<distribution version>-local,
the exact build, and its local_image, the latest build of the line (dozerdb:calendar-local
or dozerdb:lts-local). --tag replaces them for a local build.

With --push the image is built for every requested platform and pushed (a
multi-platform build cannot be loaded locally) under the tags the manifest derives,
never tags typed by the operator (--tag is rejected with --push):
  - image, the exact release (graphstack/dozerdb:2026.10.1-1.2.0);
  - <repository>:<docker_series>, the latest release of the Neo4j series
    (graphstack/dozerdb:2026.10, graphstack/dozerdb:5.26);
  - <repository>:latest, only when the manifest's line is calendar.
An LTS line therefore never moves latest or a calendar series tag. After the push,
scripts/release-gate.py --published checks that every floating tag has the exact
image's digest."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess

import release_line

ROOT = Path(__file__).resolve().parent.parent

def local_tags(manifest_path, config):
    """The tags of a local build, from the line manifest, which must agree with the archive's pins."""
    manifest = release_line.required_manifest(manifest_path, config)
    return [manifest.local_build_image, manifest.local_image]

def pushed_tags(manifest_path, config):
    """The tags of a push, from the line manifest: the exact image, the Docker series and, on calendar, latest."""
    return release_line.required_manifest(manifest_path, config).published_images

def parse_args(argv=None, manifest_path=release_line.MANIFEST, config=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--platform', help='Buildx target platform(s), e.g. linux/amd64 or linux/amd64,linux/arm64')
    parser.add_argument('--tag', action='append',
                        help="local image tag; repeatable (default: dozerdb:<distribution version>-local and the "
                             "manifest's local_image); not allowed with --push")
    parser.add_argument('--push', action='store_true',
                        help="push to the registry under the tags derived from the manifest (image, "
                             "<repository>:<docker_series>, and <repository>:latest on the calendar line) "
                             "instead of loading locally")
    args = parser.parse_args(argv)
    if args.push and args.tag:
        parser.error('--push takes its tags from the line manifest (image, <repository>:<docker_series> and, on the '
                     'calendar line, <repository>:latest); remove --tag ' + ' '.join(args.tag))
    if args.push:
        args.tag = pushed_tags(manifest_path, config)
    else:
        args.tag = args.tag or local_tags(manifest_path, config)
    return args

def build_command(args, name):
    command = ['docker', 'buildx', 'build', '--push' if args.push else '--load']
    for tag in args.tag:
        command += ['--tag', tag]
    command += ['--build-arg', f'NEO4J_TARBALL={name}']
    if args.platform:
        command += ['--platform', args.platform]
    return command

def main():
    config = release_line.read_config()
    args = parse_args(config=config)
    name = f'dozerdb-{release_line.pinned_version(config)}-unix.tar.gz'
    archive = ROOT / 'distribution/target' / name
    checksum = archive.with_name(name+'.sha256')
    expected = checksum.read_text().strip().split()
    if expected != [hashlib.sha256(archive.read_bytes()).hexdigest(), name]:
        raise SystemExit('Standalone archive checksum mismatch')
    context = ROOT / '.build/docker-context'
    if context.exists():
        shutil.rmtree(context)
    context.mkdir(parents=True)
    shutil.copy2(ROOT / 'docker/Dockerfile', context)
    shutil.copytree(ROOT / 'docker/local-package', context / 'local-package')
    shutil.copy2(archive, context)
    shutil.copy2(checksum, context)
    subprocess.run([*build_command(args, name), str(context)], check=True)

if __name__ == '__main__':
    main()
