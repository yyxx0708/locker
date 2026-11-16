import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { fileUri } from '@kit.CoreFileKit';
import { DailyPhoto } from '../data/Models';
import { FileUtil } from './FileUtil';

export class MovingPhotoLoader {
  static async load(context: common.UIAbilityContext, photo: DailyPhoto): Promise<photoAccessHelper.MovingPhoto | null> {
    if (photo.video === null) {
      return null;
    }
    const dir = FileUtil.join(context.cacheDir, 'moving-preview');
    FileUtil.ensureDir(dir);
    const stem = `${photo.personId}_${photo.epochDay}`;
    const imagePath = FileUtil.join(dir, `${stem}.jpg`);
    const videoPath = FileUtil.join(dir, `${stem}.mp4`);
    try {
      FileUtil.writeBytes(imagePath, photo.jpeg);
      FileUtil.writeBytes(videoPath, photo.video);
      return await photoAccessHelper.MediaAssetManager.loadMovingPhoto(context,
        fileUri.getUriFromPath(imagePath), fileUri.getUriFromPath(videoPath));
    } catch (err) {
      return null;
    }
  }
}
