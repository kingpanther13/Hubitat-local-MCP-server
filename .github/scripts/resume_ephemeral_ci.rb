# workflow_run executes the default branch's copy. It re-runs only a deferred or
# failed gate job of the two known workflows; that job's dependents then request
# the hub under their own gates. It holds no hub secret and never approves a deployment.
require_relative 'ephemeral_fast_ci'

module EphemeralFastCI
  def self.deferred_job(run)
    # When the last check finishes at the same time as the initial gate, wait
    # for that decision to be recorded before deciding whether to resume it.
    30.times do
      jobs = pages("actions/runs/#{Integer(run.fetch('id'))}/attempts/#{Integer(run.fetch('run_attempt'))}/jobs?per_page=100")
      gate = jobs.flat_map { |page| page.fetch('jobs') }.find { |job| job['name'] == GATE_JOB }
      # A gate that has not started yet reads the green checks itself.
      return puts("#{run.fetch('path')}: no #{GATE_JOB} job") unless gate
      if gate['status'] == 'completed'
        # A gate that failed (e.g. a GitHub API outage) is re-evaluated too.
        deferred = gate.fetch('steps').any? { |step| step['name'] == DEFERRED_STEP && step['conclusion'] == 'success' }
        return gate if gate['conclusion'] == 'failure' || (gate['conclusion'] == 'success' && deferred)
        return puts("#{run.fetch('path')}: gate #{gate['conclusion']}, not deferred")
      end
      return puts("#{run.fetch('path')}: gate #{gate['status']}") unless gate['status'] == 'in_progress'
      sleep 4
    end
    raise 'Fast CI gate did not finish'
  end

  def self.rerun(job, run)
    api("actions/jobs/#{Integer(job.fetch('id'))}/rerun", '--method', 'POST')
  rescue RuntimeError
    # The re-run may have landed although its reply was lost; repeat it only if it did not.
    return if api("actions/runs/#{Integer(run.fetch('id'))}").fetch('run_attempt') != run.fetch('run_attempt')
    api("actions/jobs/#{Integer(job.fetch('id'))}/rerun", '--method', 'POST')
  end

  def self.resume(source)
    event = source.fetch('event')
    head = { 'ref' => source.fetch('head_branch'), 'sha' => source.fetch('head_sha'), 'repo' => source.fetch('head_repository') }
    pr = nil
    number = nil
    targets = {}
    if event == 'pull_request'
      owner = head.fetch('repo').fetch('owner').fetch('login')
      query = URI.encode_www_form(state: 'open', head: "#{owner}:#{head.fetch('ref')}", per_page: 100)
      prs = pages("pulls?#{query}").flatten(1).select do |pr|
        pr.dig('head', 'sha') == head.fetch('sha') && pr.dig('head', 'repo', 'full_name') == head.dig('repo', 'full_name')
      end
      # GitHub can omit the PR association on fork runs. Never guess between PRs.
      numbers = source.fetch('pull_requests').map { |pr| pr.fetch('number') }
      prs.select! { |pr| numbers.include?(pr.fetch('number')) } unless numbers.empty?
      return puts("#{prs.size} open PRs match #{owner}:#{head.fetch('ref')}@#{head.fetch('sha')}; not resuming") unless prs.one?
      pr = prs.first
      number = pr.fetch('number')
      # The requester's hub request waits for a running consumer, so resume it first.
      targets[REQUESTER] = 'pull_request_target'
    end
    targets[CONSUMER] = event
    fast = runs(head.fetch('sha'), event, head, number)
    # Skip the PR file-list reads while an always-on check is still pending or red.
    required = number ? REQUIRED : ['unit-tests']
    return puts('Always-on fast checks are not all green yet') unless state(required, fast).all?(&:empty?)
    result = decision(pr, head, fast)
    return puts("Not resuming: #{result}") unless result == 'ready'

    targets.each do |workflow, trigger|
      latest = trigger == event ? fast : runs(head.fetch('sha'), trigger, head, number)
      run = latest[workflow_path(workflow)]
      next puts("#{workflow}: no run for #{head.fetch('sha')}") unless run
      # The prerequisite can turn green while the deferred gate's workflow
      # is still finishing. GitHub accepts job reruns only on completed runs.
      job = deferred_job(run)
      next unless job
      attempt = run.fetch('run_attempt')
      24.times do
        run = api("actions/runs/#{Integer(run.fetch('id'))}")
        break if run.fetch('status') == 'completed' || run.fetch('run_attempt') != attempt
        sleep 5
      end
      next puts("#{workflow}: already re-run (attempt #{run.fetch('run_attempt')})") if run.fetch('run_attempt') != attempt
      raise 'Deferred workflow did not finish' unless run.fetch('status') == 'completed'
      return puts("#{head.fetch('sha')} is no longer current; not resuming") unless current(head, number)
      rerun(job, run)
      puts "Resumed #{workflow} for #{head.fetch('sha')}"
    end
  end
end

if __FILE__ == $PROGRAM_NAME
  source = JSON.parse(File.read(ENV.fetch('GITHUB_EVENT_PATH'))).fetch('workflow_run')
  EphemeralFastCI.resume(source)
end
