let backendSocket = null;
let progressDragging = false;

function formatDuration(milliseconds) {
    const totalSeconds = Math.floor(milliseconds / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds - (hours * 3600)) / 60);
    const seconds = totalSeconds - (hours * 3600) - (minutes * 60);

    const paddedMinutes = minutes.toString().padStart(2, '0');
    const paddedSeconds = seconds.toString().padStart(2, '0');

    if (hours > 0) {
        return `${hours}:${paddedMinutes}:${paddedSeconds}`;
    } else {
        return `${paddedMinutes}:${paddedSeconds}`;
    }
}
function initRange(progressBar) {
  if(!progressBar) {
    console.error("No progress bar found", progressBar);
    return;
  }
  console.log("PROG", progressBar)
  //const progressBar = document.getElementById("progress-bar");
  const progressBarPoint = document.getElementById("progress-bar-point");
  const progressBarVal = document.getElementById("progress-bar-val");
  const currentTimeEl = document.getElementById("current-time");
  const currentLengthEl = document.getElementById("current-length");
  const totalLengthMs = parseFloat(currentLengthEl.getAttribute("data-length"))

  let chosenMs = null;


  function calculatePercentage(x) {
    const bounds = progressBar.getBoundingClientRect();
    const relativeX = x - bounds.left;
    const percentage = (relativeX / bounds.width) * 100;
    return Math.min(Math.max(percentage, 0), 100);
  }

  function sendPosition(milliseconds) {
    if (backendSocket) {
      backendSocket.sendImmediately(JSON.stringify({
        action: "set-time",
        milliseconds: milliseconds
      }))
    }
  }

  function updatePosition(percentage) {
    //console.log(`Setting position to ${percentage}%`);
    progressBarVal.setAttribute("style", `width: ${percentage}%`)
    progressBarPoint.setAttribute("style", `left: ${percentage}%`)
    currentTimeMs = totalLengthMs * (percentage / 100);
    currentTimeEl.innerHTML = formatDuration(currentTimeMs)
    currentLengthEl.innerHTML = "-" + formatDuration(totalLengthMs - currentTimeMs)
    chosenMs = currentTimeMs;
  }

  function downListener(event) {
    const percentage = calculatePercentage(event.clientX);
    progressDragging = true;
    updatePosition(percentage);

    function onMouseMove(event) {
      const percentage = calculatePercentage(event.clientX);
      updatePosition(percentage);
    }

    document.addEventListener('mousemove', onMouseMove);

    document.addEventListener('mouseup', function() {
      progressDragging = false;
      sendPosition(chosenMs);
      document.removeEventListener('mousemove', onMouseMove);
    }, { once: true });
  }

  function downListenerTouch(event) {
    event.preventDefault();
    progressDragging = true;
    const touch = event.touches[0];
    const percentage = calculatePercentage(touch.clientX);
    updatePosition(percentage);

    function handleMove(event) {
      const touch = event.touches[0];
      const percentage = calculatePercentage(touch.clientX);
      updatePosition(percentage);
    }

    function handleEnd() {
      progressDragging = false;
      sendPosition(chosenMs);
      document.removeEventListener('touchmove', handleMove);
      document.removeEventListener('touchend', handleEnd);
    }

    document.addEventListener('touchmove', handleMove);
    document.addEventListener('touchend', handleEnd, { once: true });

  }

  progressBar.addEventListener('mousedown', downListener);
  progressBarPoint.addEventListener('mousedown', downListener);

  progressBar.addEventListener('touchstart', downListenerTouch);
  progressBarPoint.addEventListener('touchstart', downListenerTouch);

}

function initWidgets(evt) {
  let pb = null;
  if(evt) {
    //pb = evt.target.querySelector("#progress-bar");
  } else {
    pb = document.querySelector("#progress-bar")
  }
  if(pb) {
    initRange(pb);
  }
}

htmx.onLoad(function(content) {
});

document.addEventListener("htmx:wsOpen", function (evt) {
  backendSocket = evt.detail.socketWrapper;
}),

document.addEventListener("htmx:wsClose", function (evt) {
  backendSocket = null;
}),

document.body.addEventListener("htmx:afterSettle", function(evt) {
  initWidgets(evt);
})

document.body.addEventListener("htmx:oobAfterSwap", function(evt) {
  initWidgets(evt);
})


document.addEventListener("htmx:oobBeforeSwap", function (evt) {
  const targetId = evt.detail.target.getAttribute("id");
  if(targetId == "progress-bar" || targetId == "current-time" || targetId == "current-length") {
    if(progressDragging) {
      event.preventDefault();
      return false;
    }
  }
}),


document.addEventListener("DOMContentLoaded", function() {
  initWidgets();
});

function getTabByName(name) {
  const tabs = document.querySelectorAll("#player-tabs [data-tab-name]");
  for (const tab of tabs) {
    if (tab.getAttribute("data-tab-name") == name) {
      return tab;
    }
  }
  return null;
}

function getAllTabs() {
  const tabs = document.querySelectorAll("#player-tabs [data-tab-name]");
  return tabs;
}

document.body.addEventListener("tab-change", function(evt){
  const $tab = getTabByName(evt.detail.activeTab);
  if (!$tab) return;
  $tab.classList.add("tab-active");
  const $tabs = getAllTabs();
  for (const tab of $tabs) {
    if (tab != $tab) {
      tab.classList.remove("tab-active");
    }
  }
})

htmx.config.defaultSwapStyle = 'outerHTML';
