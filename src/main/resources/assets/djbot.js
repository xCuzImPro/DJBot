urlPrefix = "http://localhost:8080";
playingVideo = false;
currentlyPlayingRequestId = 0;
waitingOnNext = false;
currentVolume = 30;

$(document).keydown(function(event){
    if(event.keyCode === 38 && event.altKey) {
        changevol(10);
    }
    if(event.keyCode === 40 && event.altKey) {
        changevol(-10);
    }
    if(event.keyCode === 39 && event.altKey) {
        nextSong(true);
    }
});

function loadSong(youtubeId, requestId, startTime) {
    var params = { allowScriptAccess: "always"};
    //switch to this one for no player controls in the embed
    //swfobject.embedSWF("https://www.youtube.com/apiplayer?video_id="+youtubeId+"&version=3&feature=player_embedded&autoplay=1&controls=1&enablejsapi=1&modestbranding=0&rel=0&showinfo=1&autohide=0&color=white&playerapiid=musicPlayer&iv_load_policy=3", "musicPlayer", "600", "400", "8", null, null, params);
    swfobject.embedSWF("https://www.youtube.com/v/"+youtubeId+"?autoplay=1&start="+startTime+"&controls=1&enablejsapi=0&iv_load_policy=3&playerapiid=musicPlayer", "musicPlayer", "600", "400", "8", null, null, params, null);
    playingVideo = true;
    currentlyPlayingRequestId = requestId;
}


function playpause() {
    var player = document.getElementById('musicPlayer');
    if(player.getPlayerState() !== 1) {
        player.playVideo();
    } else {
        player.pauseVideo();
        playingVideo = false;
    }
}

function applyVolumeChange(player, vol) {
    currentVolume = vol;
    var youtubeVol = Math.pow(Math.E, vol * 0.04605);
    player.setVolume(youtubeVol);
}

function changevol(delta) {
    var player = document.getElementById('musicPlayer');
    var newvol = currentVolume + delta;
    if(newvol > 100) newvol = 100;
    if(newvol < 10) newvol = 10;
    applyVolumeChange(player, newvol);
    $.ajax({
            dataType: 'json',
            url: urlPrefix + '/djbot/updatevolume?volume=' + newvol + '&callback=?',
            success: function(data) {
                //nothing to do here now, since we have no error reporting
            }
        })
}

function nextSong(skip) {
    if(skip) {
        var maybeSkipped = "skip=true&";
    } else {
        var maybeSkipped = "";
    }

    $.ajax({
        dataType: 'json',
        url: urlPrefix + '/djbot/next?'+maybeSkipped+'currentId=' + currentlyPlayingRequestId + '&callback=?',
        success: function(data) {
            waitingOnNext = false;
            var itWorked = false;
            if( data && data.status === 'success') {
                var newSong = data.song;
                if(newSong && newSong.requestId !== currentlyPlayingRequestId) {
                    loadSong(newSong.videoId, newSong.requestId, data.startSeconds);
                    document.getElementById('title').innerHTML=newSong.title;
                    document.getElementById('requester').innerHTML=newSong.user;
                    itWorked = true;
                }

            }
            if(!itWorked) {
                var player = document.getElementById('musicPlayer');
                if(player.getPlayerState() !== 0) {
                    //make the video end even if we got nothing from the server
                    player.seekTo(99999);
                }
            }
        }
    })
}

function loadCurrentSong() {
    $.ajax({
        dataType: 'json',
        url: urlPrefix + '/djbot/current?callback=?',
        success: function(data) {
            if(data) {
                loadSong(data.videoId, data.requestId, data.startSeconds);
                document.getElementById('title').innerHTML=data.title;
                document.getElementById('requester').innerHTML=data.user;
                justStarted = false;
                waitingOnNext = false;
            }
        }
    })
}


function update() {
    if(waitingOnNext) {
        return;
    }

    var player = document.getElementById('musicPlayer');

    if(justStarted) {
        loadCurrentSong();
        waitingOnNext = true;
        return;
    }

    try {
        //are we sitting at the end of a video and it's time to load the next?
        if( playingVideo && player && player.getPlayerState() === 0) {
            waitingOnNext = true;
            nextSong(false);
            return;
        }
    } catch (err) {
        console.log(err);
    }

    //see if we need to change the volume or skip the current song
    $.ajax({
        dataType: 'json',
        url: urlPrefix + '/djbot/check?callback=?',
        success: function(data) {
            if(data) {
                var player = document.getElementById('musicPlayer');
                applyVolumeChange(player, data.volume);
                if(data.currentSongId !== 0 && data.currentSongId !== currentlyPlayingRequestId) {
                    player.pauseVideo();
                    waitingOnNext = true;
                    loadCurrentSong();
                }
            }
        }
    })

}

justStarted = true;
setInterval(update, 1000);