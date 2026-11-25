import React, { useEffect, useRef } from 'react';

export default function MiniProgressBar({ audioRef, isPlaying }) {
  const barRef = useRef(null);

  useEffect(() => {
    let animId;

    const update = () => {
      const audio = audioRef?.current;
      if (barRef.current && audio && audio.duration && !isNaN(audio.duration) && audio.duration > 0) {
        const percent = Math.min(100, Math.max(0, (audio.currentTime / audio.duration) * 100));
        barRef.current.style.width = `${percent}%`;
      }
      if (isPlaying) {
        animId = requestAnimationFrame(update);
      }
    };

    if (isPlaying) {
      animId = requestAnimationFrame(update);
    } else {
      update();
    }

    // También escuchar timeupdate como respaldo
    const audio = audioRef?.current;
    if (audio) {
      audio.addEventListener('timeupdate', update);
      audio.addEventListener('loadedmetadata', update);
    }

    return () => {
      if (animId) cancelAnimationFrame(animId);
      if (audio) {
        audio.removeEventListener('timeupdate', update);
        audio.removeEventListener('loadedmetadata', update);
      }
    };
  }, [audioRef, isPlaying]);

  return (
    <div className="absolute top-0 left-0 right-0 h-[2px] bg-white/10 pointer-events-none">
      <div 
        ref={barRef}
        className="h-full bg-gradient-to-r from-rose-500 to-pink-500 will-change-[width]"
        style={{ width: '0%' }}
      />
    </div>
  );
}
