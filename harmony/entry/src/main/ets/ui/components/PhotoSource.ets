import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { dataSharePredicates } from '@kit.ArkData';
import { cameraPicker } from '@kit.CameraKit';
import { camera } from '@kit.CameraKit';
import { fileUri } from '@kit.CoreFileKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { systemShare } from '@kit.ShareKit';
import { promptAction } from '@kit.ArkUI';
import { PhotoProcessor } from '../../common/PhotoProcessor';
import { AppPreferences } from '../../data/AppPreferences';
import { FileUtil } from '../../common/FileUtil';

export interface PickedPhoto {
  jpeg: Uint8Array;
  video: Uint8Array | null;
}

interface GallerySelection {
  uri: string;
  moving: boolean;
}

interface MovingContent {
  image: Uint8Array;
  video: Uint8Array;
}

/**
 * 照片来源。
 *
 * 拍照走系统相机选择器（cameraPicker），相册走系统照片选择器（PhotoViewPicker）：
 * 两者都是系统 UI，App 不需要申请相机或存储权限，与 Android 版的
 * 「系统相机 + 系统照片选择器」策略一致。
 */
export class PhotoSource {
  /**
   * 让用户选择来源并取回 JPEG 封面及可选的实况视频。
   * 用户取消或读取失败时返回 null。
   */
  static async pick(
    context: common.UIAbilityContext,
    cacheDir: string,
  ): Promise<PickedPhoto | null> {
    const source = await PhotoSource.askSource(context);
    if (source === null) {
      return null;
    }
    let selection: GallerySelection | null = null;
    if (source === 'camera') {
      selection = await PhotoSource.cameraSelection(context, cacheDir);
    } else if (source === 'moving') {
      selection = await PhotoSource.pickGallerySelection(true);
    } else {
      selection = await PhotoSource.pickGallerySelection(false);
    }
    if (selection === null) {
      return null;
    }
    const watermarkAt = AppPreferences.watermarkEnabled() ? Date.now() : null;
    let moving = selection.moving;
    if (source === 'gallery' || source === 'moving') {
      try {
        const asset = await PhotoSource.getSelectedAsset(context, selection.uri);
        moving = moving || Number(asset.get(photoAccessHelper.PhotoKeys.PHOTO_SUBTYPE)) ===
          photoAccessHelper.PhotoSubtype.MOVING_PHOTO;
      } catch (err) {
        if (!moving) {
          promptAction.showToast({ message: `无法识别照片类型：${(err as Error).message ?? '请重试'}` });
          return null;
        }
      }
    }
    if (!moving) {
      const jpeg = await PhotoProcessor.loadCompressedJpeg(selection.uri, { watermarkAt });
      return jpeg === null ? null : { jpeg, video: null };
    }
    let content: MovingContent;
    try {
      content = await PhotoSource.readMovingPhoto(context, selection.uri);
    } catch (err) {
      promptAction.showToast({ message: `实况图读取失败：${(err as Error).message ?? '请重试'}` });
      return null;
    }
    const imagePath = FileUtil.join(cacheDir, `moving_${Date.now()}.image`);
    try {
      FileUtil.writeBytes(imagePath, content.image);
      const jpeg = await PhotoProcessor.loadCompressedJpeg(imagePath, { watermarkAt });
      if (jpeg === null) {
        promptAction.showToast({ message: '实况图封面无法读取' });
        return null;
      }
      const video = content.video;
      if (video.length === 0 || video.length > 60 * 1024 * 1024) {
        promptAction.showToast({ message: '实况视频无效或超过 60 MB' });
        return null;
      }
      return { jpeg, video };
    } catch (err) {
      promptAction.showToast({ message: '实况图读取失败，请重试' });
      return null;
    } finally {
      FileUtil.remove(imagePath);
    }
  }

  private static async cameraSelection(context: common.UIAbilityContext,
    cacheDir: string): Promise<GallerySelection | null> {
    const uri = await PhotoSource.takePhoto(context, cacheDir);
    return uri === null ? null : { uri, moving: false };
  }

  /** 只拍照。 */
  static async takePhoto(
    context: common.UIAbilityContext,
    cacheDir: string,
  ): Promise<string | null> {
    try {
      const result = await cameraPicker.pick(context, [cameraPicker.PickerMediaType.PHOTO], {
        cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK,
        saveUri: `${cacheDir}/camera_${Date.now()}.jpg`,
      });
      if (result.resultCode !== 0 || result.resultUri.length === 0) {
        return null;
      }
      return result.resultUri;
    } catch (err) {
      promptAction.showToast({ message: (err as Error).message ?? '无法打开相机' });
      return null;
    }
  }

  /** 只从相册选。 */
  static async pickFromGallery(): Promise<string | null> {
    const selected = await PhotoSource.pickGallerySelection();
    return selected === null ? null : selected.uri;
  }

  private static async pickGallerySelection(movingOnly: boolean = false): Promise<GallerySelection | null> {
    try {
      const picker = new photoAccessHelper.PhotoViewPicker();
      const result = await picker.select({
        MIMEType: movingOnly
          ? photoAccessHelper.PhotoViewMIMETypes.MOVING_PHOTO_IMAGE_TYPE
          : photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE,
        maxSelectNumber: 1,
        isMovingPhotoBadgeShown: true,
      });
      if (result.photoUris.length === 0) {
        return null;
      }
      const moving = movingOnly || (result.movingPhotoBadgeStates.length > 0 &&
        result.movingPhotoBadgeStates[0] === photoAccessHelper.MovingPhotoBadgeStateType.MOVING_PHOTO_ENABLED);
      return { uri: result.photoUris[0], moving };
    } catch (err) {
      return null;
    }
  }

  private static async readMovingPhoto(context: common.UIAbilityContext,
    uri: string): Promise<MovingContent> {
    const asset = await PhotoSource.getSelectedAsset(context, uri);
    const movingPhoto = await new Promise<photoAccessHelper.MovingPhoto>((resolve, reject) => {
        const handler: photoAccessHelper.MediaAssetDataHandler<photoAccessHelper.MovingPhoto> = {
          onDataPrepared: (prepared: photoAccessHelper.MovingPhoto): void => {
            if (prepared === undefined) {
              reject(new Error('实况图数据不可用'));
              return;
            }
            resolve(prepared);
          },
        };
        photoAccessHelper.MediaAssetManager.requestMovingPhoto(context, asset,
          { deliveryMode: photoAccessHelper.DeliveryMode.HIGH_QUALITY_MODE }, handler)
          .catch((err: Error) => reject(err));
      });
    const image = new Uint8Array(await movingPhoto.requestContent(photoAccessHelper.ResourceType.IMAGE_RESOURCE));
    const video = new Uint8Array(await movingPhoto.requestContent(photoAccessHelper.ResourceType.VIDEO_RESOURCE));
    return { image, video };
  }

  private static async getSelectedAsset(context: common.UIAbilityContext,
    uri: string): Promise<photoAccessHelper.PhotoAsset> {
    const predicates = new dataSharePredicates.DataSharePredicates();
    predicates.equalTo(photoAccessHelper.PhotoKeys.URI, uri);
    const helper = photoAccessHelper.getPhotoAccessHelper(context);
    const assets = await helper.getAssets({
      fetchColumns: [photoAccessHelper.PhotoKeys.URI, photoAccessHelper.PhotoKeys.PHOTO_SUBTYPE],
      predicates,
    });
    try {
      if (assets.getCount() === 0) {
        throw new Error('相册中未找到所选照片');
      }
      return await assets.getFirstObject();
    } finally {
      assets.close();
    }
  }

  /**
   * 用系统操作菜单问「拍照 / 从相册选择」。
   * 系统菜单无需自绘弹窗，也不会与页面上的自定义弹窗抢层级。
   */
  /**
   * 用系统操作菜单问「拍照 / 从相册选择」。
   * 用 promptAction.showActionMenu：不需要 UIContext，也不会与页面弹窗抢层级。
   */
  private static async askSource(context: common.UIAbilityContext): Promise<string | null> {
    try {
      const response = await promptAction.showActionMenu({
        title: '上传照片',
        buttons: [
          { text: '拍照', color: '#2A78D6' },
          { text: '从相册选择', color: '#2A78D6' },
          { text: '从相册选择动态照片', color: '#2A78D6' },
        ],
      });
      if (response.index === 0) {
        return 'camera';
      }
      if (response.index === 1) {
        return 'gallery';
      }
      if (response.index === 2) {
        return 'moving';
      }
      return null;
    } catch (err) {
      return null;
    }
  }
}

/** 系统分享一个文件。 */
export async function shareFile(
  context: common.UIAbilityContext,
  path: string,
  title: string,
  utd: string,
): Promise<void> {
  const data = new systemShare.SharedData({
    utd,
    uri: fileUri.getUriFromPath(path),
    title,
  });
  const controller = new systemShare.ShareController(data);
  await controller.show(context, {
    previewMode: systemShare.SharePreviewMode.DETAIL,
    selectionMode: systemShare.SelectionMode.SINGLE,
  });
}

/** 复制文本到剪贴板。 */
export function copyText(text: string, hint: string): void {
  try {
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    pasteboard.getSystemPasteboard().setData(data).then(() => {
      promptAction.showToast({ message: hint });
    }).catch(() => {
      promptAction.showToast({ message: '复制失败' });
    });
  } catch (err) {
    promptAction.showToast({ message: '复制失败' });
  }
}
