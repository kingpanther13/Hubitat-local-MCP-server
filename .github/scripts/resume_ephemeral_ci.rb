# workflow_run executes the default branch's copy. Re-run only a gate job from
# the two known workflows; this never dispatches a hub or approves a deployment.
require_relative 'ephemeral_fast_ci'

module EphemeralFastCI
  def self.deferred_job(run)
    # When the last check finishes at the same time as the initial gate, wait
    # for that decision to be recorded before deciding whether to resume it.
    30.times do
      jobs = pages("actions/runs/#{Integer(run.fetch('id'))}/attempts/#{Integer(run.fetch('run_attempt'))}/jobs?per_page=100")
      gate = jobs.flat_map { |page| page.fetch('jobs') }.find { |job| job['name'] == 'Fast CI prerequisites' }
      return unless gate  # A gate that starts later reads the green checks itself.
      if gate['status'] == 'completed'
        return gate if gate['conclusion'] == 'success' &&
                       gate.fetch('steps').any? { |step| step['name'] == DEFERRED_STEP && step['conclusion'] == 'success' }
        return
      end
      return if gate['status'] == 'queued'
      sleep 4
    end
    raise 'Fast CI gate did not finish'
  end

  def self.resume(source)
    event = source.fetch('event')
    head = { 'ref' => source.fetch('head_branch'), 'sha' => source.fetch('head_sha'), 'repo' => source.fetch('head_repository') }
    pr = nil
    number = nil
    targets = { CONSUMER => event }
    if event == 'pull_request'
      owner = head.fetch('repo').fetch('owner').fetch('login')
      query = URI.encode_www_form(state: 'open', head: "#{owner}:#{head.fetch('ref')}", per_page: 100)
      prs = pages("pulls?#{query}").flatten(1).select do |pr|
        pr.dig('head', 'sha') == head.fetch('sha') && pr.dig('head', 'repo', 'full_name') == head.dig('repo', 'full_name')
      end
      # GitHub can omit the PR association on fork runs. Never guess between PRs.
      numbers = source.fetch('pull_requests').map { |pr| pr.fetch('number') }
      prs.select! { |pr| numbers.include?(pr.fetch('number')) } unless numbers.empty?
      return unless prs.one?
      number = prs.first.fetch('number')
      pr = api("pulls/#{Integer(number)}")
      targets[REQUESTER] = 'pull_request_target'
    end
    fast = runs(head.fetch('sha'), event, head, number)
    required = number ? REQUIRED : ['unit-tests']
    return unless state(required, fast).all?(&:empty?)
    return unless decision(pr, head, fast) == 'ready'

    targets.each do |workflow, trigger|
      latest = trigger == event ? fast : runs(head.fetch('sha'), trigger, head, number)
      run = latest[workflow_path(workflow)]
      next unless run
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
      next if run.fetch('run_attempt') != attempt
      raise 'Deferred workflow did not finish' unless run.fetch('status') == 'completed'
      return unless current?(head, number)
      api("actions/jobs/#{Integer(job.fetch('id'))}/rerun", '--method', 'POST')
      puts "Resumed #{workflow} for #{head.fetch('sha')}"
    end
  end
end

if __FILE__ == $PROGRAM_NAME
  source = JSON.parse(File.read(ENV.fetch('GITHUB_EVENT_PATH'))).fetch('workflow_run')
  EphemeralFastCI.resume(source)
end
