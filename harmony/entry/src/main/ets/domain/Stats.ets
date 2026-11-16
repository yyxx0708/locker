import { EventColors } from '../common/EventColors';
import { EventRecord, EventTypeEntity, WearSegment } from '../data/Models';
import { YearMonth } from '../common/Dates';

/**
 * 统计口径。
 *
 * 与 Android 版一致：时长精确到分钟，跨天 / 跨月 / 跨年的分段按边界切分，
 * 月视图按天分桶，年视图按月分桶。
 */

export enum StatsMode {
  MONTH = 'MONTH',
  YEAR = 'YEAR',
}

export interface SegmentDetail {
  segment: WearSegment;
  /** 落在本期内的毫秒数。 */
  clippedMillis: number;
  /** 分段跨出了本期边界，明细里需要额外说明。 */
  crossesPeriodBoundary: boolean;
}

export interface EventTypeStat {
  type: EventTypeEntity;
  count: number;
  buckets: number[];
}

export interface StatsResult {
  totalWearMillis: number;
  eventStats: EventTypeStat[];
  wearBuckets: number[];
  bucketLabels: string[];
  segmentDetails: SegmentDetail[];
  eventDetails: EventRecord[];
}

/** 各桶的起始时刻（毫秒），末尾额外多一个区间结束点。 */
export function bucketBoundaries(mode: StatsMode, anchor: YearMonth): number[] {
  const starts: number[] = [];
  if (mode === StatsMode.MONTH) {
    const days = anchor.lengthOfMonth();
    for (let day = 1; day <= days; day++) {
      starts.push(new Date(anchor.year, anchor.month - 1, day).getTime());
    }
  } else {
    for (let month = 1; month <= 12; month++) {
      starts.push(new Date(anchor.year, month - 1, 1).getTime());
    }
  }
  const end = mode === StatsMode.MONTH
    ? anchor.endMillis()
    : new Date(anchor.year + 1, 0, 1).getTime();
  starts.push(end);
  return starts;
}

export function bucketLabels(mode: StatsMode, anchor: YearMonth): string[] {
  if (mode === StatsMode.MONTH) {
    const labels: string[] = [];
    for (let day = 1; day <= anchor.lengthOfMonth(); day++) {
      labels.push(`${day}`);
    }
    return labels;
  }
  const labels: string[] = [];
  for (let month = 1; month <= 12; month++) {
    labels.push(`${month}月`);
  }
  return labels;
}

function bucketIndex(boundaries: number[], time: number): number {
  for (let i = 0; i + 1 < boundaries.length; i++) {
    if (time >= boundaries[i] && time < boundaries[i + 1]) {
      return i;
    }
  }
  return -1;
}

export function computeStats(
  segments: WearSegment[],
  events: EventRecord[],
  eventTypes: EventTypeEntity[],
  mode: StatsMode,
  anchor: YearMonth,
  now: number,
): StatsResult {
  const boundaries = bucketBoundaries(mode, anchor);
  const periodStart = boundaries[0];
  const periodEnd = boundaries[boundaries.length - 1];
  const bucketCount = boundaries.length - 1;

  const wearBuckets: number[] = new Array<number>(bucketCount).fill(0);
  let totalWear = 0;
  const details: SegmentDetail[] = [];

  for (const segment of segments) {
    const segmentStart = segment.startAt;
    const segmentEnd = Math.max(segment.endAt === null ? now : segment.endAt, segmentStart);
    const overlapStart = Math.max(segmentStart, periodStart);
    const overlapEnd = Math.min(segmentEnd, periodEnd);

    if (overlapEnd > overlapStart) {
      totalWear += overlapEnd - overlapStart;
      for (let index = 0; index < bucketCount; index++) {
        const bucketStart = Math.max(overlapStart, boundaries[index]);
        const bucketEnd = Math.min(overlapEnd, boundaries[index + 1]);
        if (bucketEnd > bucketStart) {
          wearBuckets[index] += bucketEnd - bucketStart;
        }
      }
      details.push({
        segment,
        clippedMillis: overlapEnd - overlapStart,
        crossesPeriodBoundary: segmentStart < periodStart || segmentEnd > periodEnd,
      });
    }
  }

  const periodEvents = events.filter((e: EventRecord) => e.occurredAt >= periodStart && e.occurredAt < periodEnd);
  const countsByType = new Map<string, number>();
  const bucketsByType = new Map<string, number[]>();
  for (const event of periodEvents) {
    countsByType.set(event.type, (countsByType.get(event.type) ?? 0) + 1);
    const index = bucketIndex(boundaries, event.occurredAt);
    if (index >= 0) {
      let buckets = bucketsByType.get(event.type);
      if (buckets === undefined) {
        buckets = new Array<number>(bucketCount).fill(0);
        bucketsByType.set(event.type, buckets);
      }
      buckets[index] += 1;
    }
  }

  const orderedTypes: EventTypeEntity[] = eventTypes
    .slice()
    .sort((a, b) => (a.sortOrder - b.sortOrder) || (a.id - b.id));
  const knownKeys = orderedTypes.map((t: EventTypeEntity) => t.typeKey);
  const unknownKeys = Array.from(countsByType.keys())
    .filter((key) => knownKeys.indexOf(key) < 0)
    .sort();
  for (const key of unknownKeys) {
    const unknown: EventTypeEntity = {
      id: 0,
      typeKey: key,
      name: '未知事件',
      colorArgb: EventColors.SLATE,
      sortOrder: 2147483647,
      builtIn: false,
    };
    orderedTypes.push(unknown);
  }

  const eventStats: EventTypeStat[] = orderedTypes.map((type): EventTypeStat => {
    const empty: number[] = new Array<number>(bucketCount).fill(0);
    const stat: EventTypeStat = {
      type,
      count: countsByType.get(type.typeKey) ?? 0,
      buckets: bucketsByType.get(type.typeKey) ?? empty,
    };
    return stat;
  });

  const result: StatsResult = {
    totalWearMillis: totalWear,
    eventStats,
    wearBuckets,
    bucketLabels: bucketLabels(mode, anchor),
    segmentDetails: details.sort((a, b) => b.segment.startAt - a.segment.startAt),
    eventDetails: periodEvents.slice().sort((a, b) => b.occurredAt - a.occurredAt),
  };
  return result;
}

/** 空统计结果，用于首次渲染占位。 */
export function emptyStats(
  eventTypes: EventTypeEntity[],
  mode: StatsMode,
  anchor: YearMonth,
): StatsResult {
  return computeStats([], [], eventTypes, mode, anchor, Date.now());
}
