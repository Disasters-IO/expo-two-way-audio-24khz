import { type PermissionResponse, createPermissionHook } from "expo-modules-core";
import ExpoTwoWayAudioModule from "./ExpoTwoWayAudioModule";

export async function initialize() {
  return await ExpoTwoWayAudioModule.initialize();
}

export function playPCMData(audioData: Uint8Array) {
  return ExpoTwoWayAudioModule.playPCMData(audioData);
}

export function bypassVoiceProcessing(bypass: boolean) {
  return ExpoTwoWayAudioModule.bypassVoiceProcessing(bypass);
}

export function toggleRecording(val: boolean): boolean {
  return ExpoTwoWayAudioModule.toggleRecording(val);
}

export function isRecording(): boolean {
  return ExpoTwoWayAudioModule.isRecording();
}

export function tearDown() {
  return ExpoTwoWayAudioModule.tearDown();
}

export function restart() {
  return ExpoTwoWayAudioModule.restart();
}

export async function getMicrophonePermissionsAsync(): Promise<PermissionResponse> {
  return ExpoTwoWayAudioModule.getMicrophonePermissionsAsync();
}

export function getByteFrequencyData(): number[] {
  return ExpoTwoWayAudioModule.getByteFrequencyData();
}

// Optional optimized helper: returns a cached Uint8Array to minimize allocations at 60 Hz.
// Note: Source data from native is number[]; we copy into the same typed array each call.
let __cachedFftUint8: Uint8Array | null = null;
export function getByteFrequencyDataUint8(): Uint8Array {
  const arr: number[] = ExpoTwoWayAudioModule.getByteFrequencyData();
  if (!__cachedFftUint8 || __cachedFftUint8.length !== arr.length) {
    __cachedFftUint8 = new Uint8Array(arr.length);
  }
  for (let i = 0; i < arr.length; i++) __cachedFftUint8![i] = arr[i] | 0;
  return __cachedFftUint8!;
}

export async function requestMicrophonePermissionsAsync(): Promise<PermissionResponse> {
  return ExpoTwoWayAudioModule.requestMicrophonePermissionsAsync();
}

export function getMicrophoneModeIOS() {
  return ExpoTwoWayAudioModule.getMicrophoneModeIOS();
}

export function setMicrophoneModeIOS() {
  return ExpoTwoWayAudioModule.setMicrophoneModeIOS();
}

export function clearQueue() {
  return ExpoTwoWayAudioModule.clearQueue()
}